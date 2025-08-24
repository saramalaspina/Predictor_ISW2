package controller;

import model.JavaMethod;
import model.Release;
import model.Ticket;
import model.WekaClassifier;
import org.eclipse.jgit.revwalk.RevCommit;
import utils.PrintUtils;
import utils.ProjectConfig;
import weka.core.Instances;

import java.util.List;

public class PipelineController {

    private final String project;
    private final ProjectConfig config;

    public PipelineController(String project) {
        this.config = ProjectConfig.fromString(project);
        this.project = this.config.getProjectName();
    }

    /**
     * Execute Phase 1: Data extraction and dataset creation (Milestone 1)
     */
    public void executeDataExtraction() throws Exception {
        System.out.println("\n[PHASE 1] Extracting data from JIRA and Git...");
        ExtractFromJIRA jiraExtractor = new ExtractFromJIRA(project);
        List<Release> fullReleaseList = jiraExtractor.getReleaseList();
        System.out.println(project+": releases extracted.");

        List<Ticket> ticketList = jiraExtractor.getTicketList(fullReleaseList, true);
        PrintUtils.printTickets(project, ticketList);
        System.out.println(project+": ticket extracted.");

        ExtractFromGit gitExtractor = new ExtractFromGit(project, fullReleaseList, ticketList);
        List<RevCommit> commitList = gitExtractor.getAllCommitsAndAssignToReleases();
        fullReleaseList = gitExtractor.getFullReleaseList();
        List<Release> releaseList = gitExtractor.getReleaseList(); // first 34% of fullReleaseList
        PrintUtils.printCommits(project, commitList, "AllCommits.csv");
        System.out.println(project+": commits extracted and added to release list.");

        List<RevCommit> filteredCommitList = gitExtractor.filterCommitsAndSetToTicket();
        PrintUtils.printCommits(project, filteredCommitList, "FilteredCommits.csv");
        PrintUtils.printReleases(project, fullReleaseList, "AllReleases.csv");
        PrintUtils.printReleases(project, releaseList, "AnalysisReleases.csv");
        System.out.println(project+": commits filtered.");

        List<JavaMethod> methodList = gitExtractor.getMethodsFromReleases();
        PrintUtils.printMethods(project, methodList);
        System.out.println(project+": methods extracted.");

        gitExtractor.setMethodBuggyness(methodList);
        System.out.println(project+": method buggyness added.");

        String fullDatasetPath = "reportFiles/" + project.toLowerCase() + "/Dataset.csv";
        PrintUtils.createDataset(fullDatasetPath, methodList);
        System.out.println(project+": dataset created.");

        System.out.println("[PHASE 1] Data extraction complete.\n");
    }

    /**
     * Execute Phase 2: Classifier analysis with Weka and creation of ACUME files
     */
    public void executeClassifierAnalysis() throws Exception {
        System.out.println("\n[PHASE 2] Starting WEKA Machine Learning pipeline...");
        WekaAnalysis wekaAnalysis = new WekaAnalysis(project);
        wekaAnalysis.executeWalkForward();
        wekaAnalysis.executeCrossValidation(config.getCrossValidationRuns(), config.getCrossValidationFolds());
        System.out.println("[PHASE 2] WEKA Machine Learning pipeline complete.\n");
    }

    /**
     * Execute Phase 3: Calculation of Spearman Correlation
     */
    public void executeCorrelationAnalysis() throws Exception {
        System.out.println("\n[PHASE 3] Starting Correlation Analysis...");
        CorrelationCalculator.calculateAndSave(project);
        System.out.println("[PHASE 3] Correlation analysis complete.\n");
    }

    /**
     * Execute Phase 4: Refactoring Analysis
     */
    public void executeRefactoringAnalysis() {
        System.out.println("\n[PHASE 4] Starting Refactoring Analysis...");
        RefactoringAnalysis.execute(project, config.getAFMethod(), config.getAFeature());
        System.out.println("[PHASE 4] Refactoring analysis complete.\n");
    }

    /**
     * Execute Phase 5: What IF Analysis
     */
    public void executeWhatIfAnalysis() throws Exception {
        System.out.println("\n[PHASE 5] Starting What-If Analysis...");

        // BClassifier
        final String bestClassifierName = config.getBestClassifierName();
        final String bestSampling = config.getBestSampling();
        final String bestFeatureSelection = config.getBestFeatureSelection();
        final String bestCostSensitive = config.getBestCostSensitive();

        WekaAnalysis tempWeka = new WekaAnalysis(project);
        Instances fullDataset = tempWeka.getFullDataset();

        System.out.printf("Using BClassifier configuration: %s, Sampling=%s, FS=%s, CS=%s%n",
                bestClassifierName, bestSampling, bestFeatureSelection, bestCostSensitive);

        WekaClassifier bClassifier = ClassifierBuilder.buildSpecificClassifier(
                bestClassifierName, bestSampling, bestFeatureSelection, bestCostSensitive, fullDataset
        );

        WhatIfAnalysis whatIf = new WhatIfAnalysis(fullDataset, bClassifier, project);
        whatIf.execute();

        System.out.println("[PHASE 5] What-If Analysis complete. Results saved to whatIf.csv.\n");
    }

}
