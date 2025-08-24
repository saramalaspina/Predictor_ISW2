package controller;

import model.JavaMethod;
import model.Release;
import model.Ticket;
import model.WekaClassifier;
import org.eclipse.jgit.revwalk.RevCommit;
import utils.PipelineExecutionException;
import utils.PrintUtils;
import utils.ProjectConfig;
import weka.core.Instances;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PipelineController {

    // 1. Dichiarazione del Logger
    private static final Logger LOGGER = Logger.getLogger(PipelineController.class.getName());

    private final String project;
    private final ProjectConfig config;

    public PipelineController(String project) {
        this.config = ProjectConfig.fromString(project);
        this.project = this.config.getProjectName();
    }

    /**
     * Execute Phase 1: Data extraction and dataset creation (Milestone 1)
     */
    public void executeDataExtraction() throws PipelineExecutionException {
        try {
            LOGGER.log(Level.INFO, "\n[PHASE 1] Extracting data from JIRA and Git...");
            ExtractFromJIRA jiraExtractor = new ExtractFromJIRA(project);
            List<Release> fullReleaseList = jiraExtractor.getReleaseList();
            LOGGER.log(Level.INFO, "{0}: releases extracted.", project);

            List<Ticket> ticketList = jiraExtractor.getTicketList(fullReleaseList, true);
            PrintUtils.printTickets(project, ticketList);
            LOGGER.log(Level.INFO, "{0}: ticket extracted.", project);

            ExtractFromGit gitExtractor = new ExtractFromGit(project, fullReleaseList, ticketList);
            List<RevCommit> commitList = gitExtractor.getAllCommitsAndAssignToReleases();
            fullReleaseList = gitExtractor.getFullReleaseList();
            List<Release> releaseList = gitExtractor.getReleaseList(); // first 34% of fullReleaseList
            PrintUtils.printCommits(project, commitList, "AllCommits.csv");
            LOGGER.log(Level.INFO, "{0}: commits extracted and added to release list.", project);

            List<RevCommit> filteredCommitList = gitExtractor.filterCommitsAndSetToTicket();
            PrintUtils.printCommits(project, filteredCommitList, "FilteredCommits.csv");
            PrintUtils.printReleases(project, fullReleaseList, "AllReleases.csv");
            PrintUtils.printReleases(project, releaseList, "AnalysisReleases.csv");
            LOGGER.log(Level.INFO, "{0}: commits filtered.", project);

            List<JavaMethod> methodList = gitExtractor.getMethodsFromReleases();
            PrintUtils.printMethods(project, methodList);
            LOGGER.log(Level.INFO, "{0}: methods extracted.", project);

            gitExtractor.setMethodBuggyness(methodList);
            LOGGER.log(Level.INFO, "{0}: method buggyness added.", project);

            String fullDatasetPath = "reportFiles/" + project.toLowerCase() + "/Dataset.csv";
            PrintUtils.createDataset(fullDatasetPath, methodList);
            LOGGER.log(Level.INFO, "{0}: dataset created.", project);

            LOGGER.log(Level.INFO, "[PHASE 1] Data extraction complete.\n");
        } catch (Exception e) {
            throw new PipelineExecutionException("Failed during Phase 1: Data Extraction", e);
        }
    }

    /**
     * Execute Phase 2: Classifier analysis with Weka and creation of ACUME files
     */
    public void executeClassifierAnalysis() throws PipelineExecutionException {
        try {
            LOGGER.log(Level.INFO, "\n[PHASE 2] Starting WEKA Machine Learning pipeline...");
            WekaAnalysis wekaAnalysis = new WekaAnalysis(project);
            wekaAnalysis.executeWalkForward();
            wekaAnalysis.executeCrossValidation(config.getCrossValidationRuns(), config.getCrossValidationFolds());
            LOGGER.log(Level.INFO, "[PHASE 2] WEKA Machine Learning pipeline complete.\n");
        } catch (Exception e) {
            throw new PipelineExecutionException("Failed during Phase 2: Classifier Analysis", e);
        }

    }

    /**
     * Execute Phase 3: Calculation of Spearman Correlation
     */
    public void executeCorrelationAnalysis() throws PipelineExecutionException {
        try {
            LOGGER.log(Level.INFO, "\n[PHASE 3] Starting Correlation Analysis...");
            CorrelationCalculator.calculateAndSave(project);
            LOGGER.log(Level.INFO, "[PHASE 3] Correlation analysis complete.\n");
        } catch (Exception e) {
            throw new PipelineExecutionException("Failed during Phase 3: Calculation of Spearman Correlation", e);
        }
    }

    /**
     * Execute Phase 4: Refactoring Analysis
     */
    public void executeRefactoringAnalysis() {
        LOGGER.log(Level.INFO, "\n[PHASE 4] Starting Refactoring Analysis...");
        RefactoringAnalysis.execute(project, config.getAFMethod(), config.getAFeature());
        LOGGER.log(Level.INFO, "[PHASE 4] Refactoring analysis complete.\n");
    }

    /**
     * Execute Phase 5: What IF Analysis
     */
    public void executeWhatIfAnalysis() throws PipelineExecutionException {

        try {
            LOGGER.log(Level.INFO, "\n[PHASE 5] Starting What-If Analysis...");

            // BClassifier
            final String bestClassifierName = config.getBestClassifierName();
            final String bestSampling = config.getBestSampling();
            final String bestFeatureSelection = config.getBestFeatureSelection();
            final String bestCostSensitive = config.getBestCostSensitive();

            WekaAnalysis tempWeka = new WekaAnalysis(project);
            Instances fullDataset = tempWeka.getFullDataset();

            LOGGER.log(Level.INFO, "Using BClassifier configuration: {0}, Sampling={1}, FS={2}, CS={3}",
                    new Object[]{bestClassifierName, bestSampling, bestFeatureSelection, bestCostSensitive});

            WekaClassifier bClassifier = ClassifierBuilder.buildSpecificClassifier(
                    bestClassifierName, bestSampling, bestFeatureSelection, bestCostSensitive, fullDataset
            );

            WhatIfAnalysis whatIf = new WhatIfAnalysis(fullDataset, bClassifier, project);
            whatIf.execute();

            LOGGER.log(Level.INFO, "[PHASE 5] What-If Analysis complete. Results saved to whatIf.csv.\n");
        } catch (Exception e) {
            throw new PipelineExecutionException("Failed during Phase 5: What-If Analysis", e);
        }

    }

}