import controller.ExtractFromGit;
import controller.ExtractFromJIRA;
import controller.WekaAnalysis;
import model.JavaMethod;
import model.Release;
import model.Ticket;
import org.eclipse.jgit.revwalk.RevCommit;
import utils.PrintUtils;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


public class Main {

    public static void main(String[] args) throws Exception {
        Logger rootLogger = Logger.getLogger("");
        rootLogger.setLevel(Level.SEVERE);

        String project = "BOOKKEEPER";
        //String project = "OPENJPA";

        System.out.println("-------------------------------------------");
        System.out.println("Starting analysis for project: " + project.toUpperCase());
        System.out.println("-------------------------------------------");

        // --- PHASE 1: DATA EXTRACTION ---
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
        ticketList = gitExtractor.getTicketList();
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

        // --- PHASE 2: WEKA MACHINE LEARNING ANALYSIS ---
        System.out.println("[PHASE 2] Starting WEKA Machine Learning pipeline...");

        WekaAnalysis wekaAnalysis = new WekaAnalysis(project);
        //wekaAnalysis.executeWalkForward();
        wekaAnalysis.executeCrossValidation();

        System.out.println("[PHASE 2] WEKA Machine Learning pipeline complete.\n");

        // --- PHASE 3: ANALISI DI CORRELAZIONE ---
        System.out.println("[PHASE 3] Starting Correlation Analysis...");
        controller.CorrelationCalculator.calculateAndSave(project);
        System.out.println(project+": correlation calculated.");

        System.out.println("-------------------------------------------");
        System.out.println("Analysis for " + project.toUpperCase() + " has finished successfully.");
        System.out.println("-------------------------------------------");

    }

}
