import controller.ExtractFromGit;
import controller.ExtractFromJIRA;
import model.JavaMethod;
import model.Release;
import model.Ticket;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import utils.PrintUtils;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;


public class Main {

    public static void main(String[] args) throws IOException, URISyntaxException, GitAPIException {
        //String project = "BOOKKEEPER";
        String project = "OPENJPA";

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

        PrintUtils.createDataset(project, methodList);
        System.out.println(project+": dataset created.");

    }
}
