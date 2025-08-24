package controller;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;

import model.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import utils.GitUtils;

import java.io.File;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static controller.MetricCalculator.*;


public class ExtractFromGit {
    private static final Logger LOGGER = Logger.getLogger(ExtractFromGit.class.getName());

    private final List<Ticket> ticketList;
    private List<Release> releaseList; // first 34% of releases
    private final List<Release> fullReleaseList;
    private final List<RevCommit> commitList;

    private final Git git;
    private final Repository repository;

    public ExtractFromGit(String projectName, List<Release> allReleases, List<Ticket> ticketList) throws IOException {
        File repoDir = new File("/Users/saramalaspina/Desktop/" + projectName.toLowerCase() + "_isw2");
        File gitDir = new File(repoDir, ".git");

        if (!gitDir.exists()) {
            LOGGER.log(Level.SEVERE, "Error: directory .git not found in {0}", repoDir.getAbsolutePath());
        }

        try {
            this.git = Git.open(repoDir);
        } catch (IOException e) {
            throw new IOException("Impossible to open the repository Git in " + repoDir.getAbsolutePath(), e);
        }

        this.repository = git.getRepository();
        this.fullReleaseList = new ArrayList<>(allReleases);
        this.fullReleaseList.sort(Comparator.comparing(Release::getDate));
        this.releaseList = new ArrayList<>();
        this.ticketList = ticketList;
        this.commitList = new ArrayList<>();
    }

    public List<Release> getReleaseList() {
        return releaseList;
    }

    public List<Release> getFullReleaseList() {
        return fullReleaseList;
    }

    public void setReleaseListForAnalysis() {
        if (this.fullReleaseList == null || this.fullReleaseList.isEmpty()) {
            return;
        }
        // Ignore the last 66% releases
        int releasesToConsider = (int) Math.ceil(this.fullReleaseList.size() * 0.34);
        if (releasesToConsider == 0 && !this.fullReleaseList.isEmpty()) {
            releasesToConsider = 1;
        }

        this.releaseList = new ArrayList<>(this.fullReleaseList.subList(0, releasesToConsider));

        int i = 0;
        for (Release release : this.releaseList) {
            release.setId(++i);
        }
    }

    public List<RevCommit> getAllCommitsAndAssignToReleases() throws GitAPIException, IOException {

        if (this.ticketList == null) {
            LOGGER.log(Level.SEVERE, "Error: Ticket list not initialized");
            return Collections.emptyList();
        }

        if (!commitList.isEmpty()) {
            return commitList;
        }

        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
        Iterable<RevCommit> commitsIterable = git.log().all().call();
        commitsIterable.forEach(commitList::add);
        // Sort the commits from the latest to the newest
        commitList.sort(Comparator.comparing(c -> c.getCommitterIdent().getWhen()));

        for (RevCommit commit : commitList) {
            LocalDate commitDate = LocalDate.parse(formatter.format(commit.getCommitterIdent().getWhen()));
            LocalDate lowerBoundDate = LocalDate.parse(formatter.format(new Date(0)));

            // Assign commits to release
            for (Release release : this.fullReleaseList) {
                LocalDate releaseDate = release.getDate();
                if (!commitDate.isBefore(lowerBoundDate) && !commitDate.isAfter(releaseDate)) {
                    release.addCommit(commit);
                }
                lowerBoundDate = releaseDate;
            }
        }

        filterAndRenumberReleases();

        setReleaseListForAnalysis();

        return commitList;
    }

    public List<RevCommit> filterCommitsAndSetToTicket() {

        List<RevCommit> filteredCommits = new ArrayList<>();
        if (commitList.isEmpty()) {
            LOGGER.log(Level.SEVERE, "Error: list commit empty. First call getAllCommitsAndAssignToReleases().");
            return filteredCommits;
        }

        for (RevCommit commit : commitList) {
            for (Ticket ticket : this.ticketList) {
                String commitMessage = commit.getFullMessage();
                String ticketID = ticket.getId();

                SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
                LocalDate commitDate = LocalDate.parse(formatter.format(commit.getCommitterIdent().getWhen()));

                if (ticketID != null && !ticketID.isEmpty() &&
                        commitMessage.contains(ticketID) &&
                        ticket.getResolutionDate() != null && !commitDate.isAfter(ticket.getResolutionDate()) &&
                        ticket.getCreationDate() != null && !commitDate.isBefore(ticket.getCreationDate())) {

                    if (!filteredCommits.contains(commit)) {
                        filteredCommits.add(commit);
                    }
                    ticket.addCommit(commit);
                }
            }
        }

        this.ticketList.removeIf(ticket -> ticket.getCommitList().isEmpty());
        return filteredCommits;
    }

    public List<JavaMethod> getMethodsFromReleases() throws IOException, GitAPIException {
        List<JavaMethod> allMethodsOfReleases = new ArrayList<>();
        Set<String> processedMethodsForRelease = new HashSet<>();

        for (Release release : this.releaseList) {
            processedMethodsForRelease.clear();
            List<RevCommit> releaseCommits = release.getCommitList();
            if (releaseCommits.isEmpty()) continue;

            releaseCommits.sort(Comparator.comparing(c -> c.getCommitterIdent().getWhen()));
            RevCommit lastCommitOfRelease = releaseCommits.get(releaseCommits.size() - 1);

            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(lastCommitOfRelease.getTree());
                treeWalk.setRecursive(true);

                while (treeWalk.next()) {
                    String filePath = treeWalk.getPathString();
                    if (filePath.endsWith(".java") && !filePath.contains("/test/")) {
                        ObjectLoader loader = repository.open(treeWalk.getObjectId(0));
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        loader.copyTo(output);
                        String fileContent = output.toString();

                        try {
                            CompilationUnit cu = StaticJavaParser.parse(fileContent);
                            cu.findAll(MethodDeclaration.class).forEach(md -> {
                                String methodSignature = JavaMethod.getSignature(md);
                                String fqn = filePath + "/" + methodSignature;

                                if (!processedMethodsForRelease.contains(fqn)) {
                                    JavaMethod currentReleaseMethod = new JavaMethod(fqn, release);
                                    currentReleaseMethod.setBodyHash(GitUtils.calculateBodyHash(md));

                                    // Calculate Metrics
                                    int currentLoc = calculateLOC(md);
                                    currentReleaseMethod.setLoc(currentLoc);
                                    int numParameters = md.getParameters().size();
                                    currentReleaseMethod.setNumParameters(numParameters);
                                    int branches = calculateNumberOfBranches(md);
                                    currentReleaseMethod.setNumberOfBranches(branches);
                                    int nestingDepth = calculateNestingDepth(md);
                                    currentReleaseMethod.setNestingDepth(nestingDepth);
                                    int smellCount = calculateCodeSmells(md, branches, currentLoc, nestingDepth, numParameters);
                                    currentReleaseMethod.setNumberOfCodeSmells(smellCount);

                                    allMethodsOfReleases.add(currentReleaseMethod);
                                    release.addMethod(currentReleaseMethod); // Associa il metodo alla sua release
                                    currentReleaseMethod.setBodyHash(GitUtils.calculateBodyHash(md));
                                    processedMethodsForRelease.add(fqn);
                                }
                            });
                        } catch (ParseProblemException | StackOverflowError e) {
                            System.err.println("Errore di parsing per il file: " + filePath + " nel commit " + lastCommitOfRelease.getName() + ". " + e.getMessage());
                        }
                    }
                }
            }
        }

        // Associazione commit ai metodi (storico)
        addCommitsToMethods(allMethodsOfReleases, this.commitList);

        calculateNFix(allMethodsOfReleases, this.ticketList, this.releaseList);

        return allMethodsOfReleases;
    }

    public void addCommitsToMethods(List<JavaMethod> allMethods, List<RevCommit> commitListInput) throws IOException, GitAPIException {
        List<RevCommit> sortedCommits = new ArrayList<>(commitListInput);
        sortedCommits.sort(Comparator.comparing(RevCommit::getCommitTime));

        for (RevCommit commit : sortedCommits) {
            if (commit.getParentCount() == 0) {
                continue;
            }

            RevCommit parent = commit.getParent(0);
            List<DiffEntry> diffs = GitUtils.getDiffEntries(parent, commit, repository);

            Map<String, String> oldFileContents = GitUtils.getFileContents(parent, diffs, true, repository);
            Map<String, String> newFileContents = GitUtils.getFileContents(commit, diffs, false, repository);

            for (DiffEntry diff : diffs) {
                String filePath;

                if (diff.getChangeType() == DiffEntry.ChangeType.DELETE) {
                    filePath = diff.getOldPath();
                } else {
                    filePath = diff.getNewPath();
                }

                if (!filePath.endsWith(".java") || filePath.contains("/test/")) {
                    continue;
                }

                String oldContent = oldFileContents.getOrDefault(diff.getOldPath(), "");
                String newContent = newFileContents.getOrDefault(diff.getNewPath(), "");

                Map<String, MethodDeclaration> oldMethods = GitUtils.parseMethods(oldContent);
                Map<String, MethodDeclaration> newMethods = GitUtils.parseMethods(newContent);

                for (Map.Entry<String, MethodDeclaration> newMethodEntry : newMethods.entrySet()) {
                    String signature = newMethodEntry.getKey();
                    MethodDeclaration newMd = newMethodEntry.getValue();
                    MethodDeclaration oldMd = oldMethods.get(signature);

                    String newBodyHash = GitUtils.calculateBodyHash(newMd);
                    String oldBodyHash = (oldMd != null) ? GitUtils.calculateBodyHash(oldMd) : null;

                    boolean changed;
                    if (oldMd == null) {
                        changed = true;
                    } else {
                        changed = !Objects.equals(oldBodyHash, newBodyHash);
                    }

                    if (changed) {
                        updateMethodMetricsForCommit(allMethods, filePath, newMd, commit, oldMd, newMd);
                    }
                }
            }
        }

        // Calculate number of authors after all the commits are processed
        for (JavaMethod method : allMethods) {
            if (method.getCommits() != null && !method.getCommits().isEmpty()) {
                Set<String> authors = method.getCommits().stream()
                        .map(c -> c.getAuthorIdent().getName())
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
                method.setNumAuthors(authors.size());
            } else {
                method.setNumAuthors(0);
            }
        }
    }

    private void updateMethodMetricsForCommit(List<JavaMethod> allProjectMethods, String filePath,
                                              MethodDeclaration currentMdAstInCommit, RevCommit commit,
                                              MethodDeclaration oldMdAstInParentCommit, MethodDeclaration newMdAstInCommit) {
        String signature = JavaMethod.getSignature(currentMdAstInCommit);
        String fqn = filePath + "/" + signature;
        Release releaseOfCommit = GitUtils.getReleaseOfCommit(commit, this.fullReleaseList);

        if (releaseOfCommit == null) return;

        for (JavaMethod projectMethod : allProjectMethods) {
            if (projectMethod.getFullyQualifiedName().equals(fqn) &&
                    !projectMethod.getRelease().getDate().isBefore(releaseOfCommit.getDate())) { // projectMethod.releaseDate >= commit.releaseDate

                projectMethod.addCommit(commit);
                projectMethod.incrementNumRevisions();

                int addedInThisCommit = 0;
                int deletedInThisCommit = 0;

                int locNewInCommit = calculateLOC(newMdAstInCommit);

                if (oldMdAstInParentCommit != null) {
                    int locOldInParentCommit = calculateLOC(oldMdAstInParentCommit);

                    if (locNewInCommit > locOldInParentCommit) {
                        addedInThisCommit = locNewInCommit - locOldInParentCommit;
                    }
                    if (locOldInParentCommit > locNewInCommit) {
                        deletedInThisCommit = locOldInParentCommit - locNewInCommit;
                    }
                } else {
                    addedInThisCommit = locNewInCommit;
                }

                projectMethod.addStmtAdded(addedInThisCommit);
                projectMethod.addStmtDeleted(deletedInThisCommit);

                int churnForThisCommit = addedInThisCommit + deletedInThisCommit;
                projectMethod.updateMaxChurn(churnForThisCommit);

            }
        }
    }


    public void setMethodBuggyness(List<JavaMethod> allProjectMethods) {
        if (this.ticketList == null) {
            System.err.println("Ticket list non inizializzata.");
            return;
        }

        for (JavaMethod projectMethod : allProjectMethods) {
            projectMethod.setBuggy(false);
        }

        for (Ticket ticket : this.ticketList) {
            Release injectedVersion = ticket.getIv();
            if (injectedVersion != null) {
                for (RevCommit fixCommit : ticket.getCommitList()) {
                    processFixCommit(fixCommit, injectedVersion, allProjectMethods);
                }
            }
        }
    }


    private void processFixCommit(RevCommit fixCommit, Release injectedVersion, List<JavaMethod> allProjectMethods) {
        Release fixedVersion = GitUtils.getReleaseOfCommit(fixCommit, this.fullReleaseList);
        if (fixedVersion == null || fixCommit.getParentCount() == 0) {
            return;
        }

        try {
            RevCommit parentOfFix = fixCommit.getParent(0);
            List<DiffEntry> diffs = GitUtils.getDiffEntries(parentOfFix, fixCommit, repository);
            Map<String, String> newFileContentsInFix = GitUtils.getFileContents(fixCommit, diffs, false, repository);

            for (DiffEntry diff : diffs) {
                processDiff(diff, parentOfFix, newFileContentsInFix, injectedVersion, fixedVersion, fixCommit, allProjectMethods);
            }
        } catch (IOException | GitAPIException e) {
            System.err.println("Errore durante l'analisi del commit di fix " + fixCommit.getName() + ": " + e.getMessage());
        }
    }


    private void processDiff(DiffEntry diff, RevCommit parent, Map<String, String> newContents, Release iv, Release fv, RevCommit fc, List<JavaMethod> methods) throws IOException {
        String filePath = diff.getNewPath();
        if (!filePath.endsWith(".java") || filePath.contains("/test/")) {
            return;
        }

        String newContent = newContents.getOrDefault(filePath, "");
        Map<String, MethodDeclaration> newMethodsInFix = GitUtils.parseMethods(newContent);

        String oldContentInFix = GitUtils.getFileContents(parent, Collections.singletonList(diff), true, repository).getOrDefault(diff.getOldPath(), "");
        Map<String, MethodDeclaration> oldMethodsInFix = GitUtils.parseMethods(oldContentInFix);

        for (Map.Entry<String, MethodDeclaration> fixedMethodEntry : newMethodsInFix.entrySet()) {
            String signature = fixedMethodEntry.getKey();
            MethodDeclaration fixedMd = fixedMethodEntry.getValue();
            MethodDeclaration preFixMd = oldMethodsInFix.get(signature);

            String hashFixed = GitUtils.calculateBodyHash(fixedMd);
            String hashPreFix = GitUtils.calculateBodyHash(preFixMd);

            boolean actuallyChangedByFix = (preFixMd == null && fixedMd != null) ||
                    (hashPreFix != null && hashFixed != null && !hashPreFix.equals(hashFixed)) ||
                    (hashPreFix == null && hashFixed != null && preFixMd != null) ||
                    (hashPreFix != null && hashFixed == null && fixedMd != null);

            if (actuallyChangedByFix) {
                String fqn = filePath + "/" + signature;
                labelBuggyMethods(fqn, iv, fv, fc, methods);
            }
        }
    }

    private void labelBuggyMethods(String fixedMethodFQN, Release injectedVersion, Release fixedVersion, RevCommit fixCommit, List<JavaMethod> allProjectMethods) {
        for (JavaMethod projectMethod : allProjectMethods) {
            if (projectMethod.getFullyQualifiedName().equals(fixedMethodFQN)) {
                // Add the fix commit if the method belongs to the FV and the commit has touched it
                if (projectMethod.getRelease().getId() == fixedVersion.getId() && projectMethod.getCommits().contains(fixCommit) ) { // Assumiamo che getCommits contenga tutti i commit che hanno toccato il metodo in quella release
                    projectMethod.addFixCommit(fixCommit);
                }
                // Label as buggy if the method's release is between  IV (included) e FV (excluded)
                if (projectMethod.getRelease().getId() >= injectedVersion.getId() &&
                        projectMethod.getRelease().getId() < fixedVersion.getId()) {
                    projectMethod.setBuggy(true);
                }
            }
        }
    }

    private void filterAndRenumberReleases() {
        // Remove releases with 0 commit
        this.fullReleaseList.removeIf(release -> release.getCommitList().isEmpty());

        int idCounter = 1;
        for (Release r : this.fullReleaseList) {
            r.setId(idCounter++);
        }

    }

}


