package controller;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import model.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import utils.GitUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static controller.MetricCalculator.*;

public class ExtractFromGit {
    private static final Logger LOGGER = Logger.getLogger(ExtractFromGit.class.getName());

    private static final String JAVA_EXTENSION = ".java";
    private static final String TEST_FOLDER = "/test/";
    private static final String DATE_FORMAT_PATTERN = "yyyy-MM-dd";

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
        if (this.fullReleaseList.isEmpty()) {
            return;
        }

        int releasesToConsider = (int) Math.ceil(this.fullReleaseList.size() * 0.34);
        if (releasesToConsider == 0) {
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

        Iterable<RevCommit> commitsIterable = git.log().all().call();
        commitsIterable.forEach(commitList::add);
        commitList.sort(Comparator.comparing(c -> c.getCommitterIdent().getWhen()));

        assignCommitsToReleases();
        filterAndRenumberReleases();
        setReleaseListForAnalysis();

        return commitList;
    }

    private void assignCommitsToReleases() {
        SimpleDateFormat formatter = new SimpleDateFormat(DATE_FORMAT_PATTERN);
        for (RevCommit commit : commitList) {
            LocalDate commitDate = LocalDate.parse(formatter.format(commit.getCommitterIdent().getWhen()));
            LocalDate lowerBoundDate = LocalDate.parse(formatter.format(new Date(0)));

            for (Release release : this.fullReleaseList) {
                LocalDate releaseDate = release.getDate();
                if (!commitDate.isBefore(lowerBoundDate) && !commitDate.isAfter(releaseDate)) {
                    release.addCommit(commit);
                }
                lowerBoundDate = releaseDate;
            }
        }
    }


    public List<RevCommit> filterCommitsAndSetToTicket() {
        List<RevCommit> filteredCommits = new ArrayList<>();
        if (commitList.isEmpty()) {
            LOGGER.log(Level.SEVERE, "Error: list commit empty. First call getAllCommitsAndAssignToReleases().");
            return filteredCommits;
        }

        SimpleDateFormat formatter = new SimpleDateFormat(DATE_FORMAT_PATTERN);

        for (RevCommit commit : commitList) {
            for (Ticket ticket : this.ticketList) {
                if (isCommitRelatedToTicket(commit, ticket, formatter)) {
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

    private boolean isCommitRelatedToTicket(RevCommit commit, Ticket ticket, SimpleDateFormat formatter) {
        String commitMessage = commit.getFullMessage();
        String ticketID = ticket.getId();
        LocalDate commitDate = LocalDate.parse(formatter.format(commit.getCommitterIdent().getWhen()));

        return ticketID != null && !ticketID.isEmpty() &&
                commitMessage.contains(ticketID) &&
                ticket.getResolutionDate() != null && !commitDate.isAfter(ticket.getResolutionDate()) &&
                ticket.getCreationDate() != null && !commitDate.isBefore(ticket.getCreationDate());
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

            processFilesInReleaseCommit(lastCommitOfRelease, release, allMethodsOfReleases, processedMethodsForRelease);
        }

        addCommitsToMethods(allMethodsOfReleases, this.commitList);
        calculateNFix(allMethodsOfReleases, this.ticketList, this.releaseList);

        return allMethodsOfReleases;
    }

    private void processFilesInReleaseCommit(RevCommit commit, Release release,
                                             List<JavaMethod> allMethodsOfReleases,
                                             Set<String> processedMethodsForRelease) throws IOException {
        try (TreeWalk treeWalk = new TreeWalk(repository)) {
            treeWalk.addTree(commit.getTree());
            treeWalk.setRecursive(true);

            while (treeWalk.next()) {
                String filePath = treeWalk.getPathString();
                if (filePath.endsWith(JAVA_EXTENSION) && !filePath.contains(TEST_FOLDER)) {
                    processJavaFile(filePath, commit, release, allMethodsOfReleases, processedMethodsForRelease);
                }
            }
        }
    }

    private void processJavaFile(String filePath, RevCommit commit, Release release,
                                 List<JavaMethod> allMethodsOfReleases,
                                 Set<String> processedMethodsForRelease) throws IOException {
        ObjectLoader loader = repository.open(Objects.requireNonNull(repository.resolve(commit.getName() + ":" + filePath)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        loader.copyTo(output);
        String fileContent = output.toString();

        try {
            CompilationUnit cu = StaticJavaParser.parse(fileContent);
            cu.findAll(MethodDeclaration.class).forEach(md -> {
                String methodSignature = JavaMethod.getSignature(md);
                String fqn = filePath + "/" + methodSignature;

                if (!processedMethodsForRelease.contains(fqn)) {
                    JavaMethod currentReleaseMethod = createAndConfigureJavaMethod(fqn, release, md);
                    allMethodsOfReleases.add(currentReleaseMethod);
                    release.addMethod(currentReleaseMethod);
                    processedMethodsForRelease.add(fqn);
                }
            });
        } catch (ParseProblemException | StackOverflowError e) {
            LOGGER.log(Level.SEVERE, "Parsing error for file: {0} in commit {1}. {2}", new Object[]{filePath, commit.getName(), e.getMessage()});
        }
    }

    private JavaMethod createAndConfigureJavaMethod(String fqn, Release release, MethodDeclaration md) {
        JavaMethod method = new JavaMethod(fqn, release);
        method.setBodyHash(GitUtils.calculateBodyHash(md));

        method.setLoc(calculateLOC(md));
        method.setNumParameters(md.getParameters().size());
        int branches = calculateNumberOfBranches(md);
        method.setNumberOfBranches(branches);
        int nestingDepth = calculateNestingDepth(md);
        method.setNestingDepth(nestingDepth);
        method.setNumberOfCodeSmells(calculateCodeSmells(md, branches, method.getLoc(), nestingDepth, method.getNumParameters()));

        return method;
    }


    public void addCommitsToMethods(List<JavaMethod> allMethods, List<RevCommit> commitListInput) throws IOException, GitAPIException {
        List<RevCommit> sortedCommits = new ArrayList<>(commitListInput);
        sortedCommits.sort(Comparator.comparing(RevCommit::getCommitTime));

        for (RevCommit commit : sortedCommits) {
            if (commit.getParentCount() == 0) {
                continue;
            }
            processCommitForMethodHistory(commit, allMethods);
        }

        // Calculate number of authors after all the commits are processed
        updateNumAuthorsForMethods(allMethods);
    }

    private void processCommitForMethodHistory(RevCommit commit, List<JavaMethod> allMethods) throws IOException, GitAPIException {
        RevCommit parent = commit.getParent(0);
        List<DiffEntry> diffs = GitUtils.getDiffEntries(parent, commit, repository);

        Map<String, String> oldFileContents = GitUtils.getFileContents(parent, diffs, true, repository);
        Map<String, String> newFileContents = GitUtils.getFileContents(commit, diffs, false, repository);

        for (DiffEntry diff : diffs) {
            processDiffEntryForMethodHistory(diff, commit, oldFileContents, newFileContents, allMethods);
        }
    }

    private void processDiffEntryForMethodHistory(DiffEntry diff, RevCommit commit,
                                                  Map<String, String> oldFileContents,
                                                  Map<String, String> newFileContents,
                                                  List<JavaMethod> allMethods) {
        String filePath = (diff.getChangeType() == DiffEntry.ChangeType.DELETE) ? diff.getOldPath() : diff.getNewPath();

        if (!filePath.endsWith(JAVA_EXTENSION) || filePath.contains(TEST_FOLDER)) {
            return;
        }

        String oldContent = oldFileContents.getOrDefault(diff.getOldPath(), "");
        String newContent = newFileContents.getOrDefault(diff.getNewPath(), "");

        Map<String, MethodDeclaration> oldMethods = GitUtils.parseMethods(oldContent);
        Map<String, MethodDeclaration> newMethods = GitUtils.parseMethods(newContent);

        for (Map.Entry<String, MethodDeclaration> newMethodEntry : newMethods.entrySet()) {
            String signature = newMethodEntry.getKey();
            MethodDeclaration newMd = newMethodEntry.getValue();
            MethodDeclaration oldMd = oldMethods.get(signature);

            if (methodBodyChanged(oldMd, newMd)) {
                updateMethodMetricsForCommit(allMethods, filePath, newMd, commit, oldMd, newMd);
            }
        }
    }

    private boolean methodBodyChanged(MethodDeclaration oldMd, MethodDeclaration newMd) {
        String newBodyHash = GitUtils.calculateBodyHash(newMd);
        String oldBodyHash = (oldMd != null) ? GitUtils.calculateBodyHash(oldMd) : null;
        return !Objects.equals(oldBodyHash, newBodyHash);
    }

    private void updateNumAuthorsForMethods(List<JavaMethod> allMethods) {
        for (JavaMethod method : allMethods) {
            Set<String> authors = method.getCommits().stream()
                    .map(c -> c.getAuthorIdent().getName())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            method.setNumAuthors(authors.size());
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
            if (isMatchingMethodForUpdate(projectMethod, fqn, releaseOfCommit)) {
                applyMetricsUpdateToMethod(projectMethod, commit, oldMdAstInParentCommit, newMdAstInCommit);
            }
        }
    }

    private boolean isMatchingMethodForUpdate(JavaMethod projectMethod, String fqn, Release releaseOfCommit) {
        return projectMethod.getFullyQualifiedName().equals(fqn) &&
                !projectMethod.getRelease().getDate().isBefore(releaseOfCommit.getDate());
    }

    private void applyMetricsUpdateToMethod(JavaMethod projectMethod, RevCommit commit,
                                            MethodDeclaration oldMdAstInParentCommit, MethodDeclaration newMdAstInCommit) {
        projectMethod.addCommit(commit);
        projectMethod.incrementNumRevisions();

        int addedInThisCommit = 0;
        int deletedInThisCommit = 0;

        int locNewInCommit = calculateLOC(newMdAstInCommit);

        if (oldMdAstInParentCommit != null) {
            int locOldInParentCommit = calculateLOC(oldMdAstInParentCommit);
            addedInThisCommit = Math.max(0, locNewInCommit - locOldInParentCommit);
            deletedInThisCommit = Math.max(0, locOldInParentCommit - locNewInCommit);
        } else {
            addedInThisCommit = locNewInCommit;
        }

        projectMethod.addStmtAdded(addedInThisCommit);
        projectMethod.addStmtDeleted(deletedInThisCommit);

        int churnForThisCommit = addedInThisCommit + deletedInThisCommit;
        projectMethod.updateMaxChurn(churnForThisCommit);
    }


    public void setMethodBuggyness(List<JavaMethod> allProjectMethods) {
        if (this.ticketList == null) {
            LOGGER.log(Level.SEVERE, "Ticket list not initialized.");
            return;
        }

        allProjectMethods.forEach(method -> method.setBuggy(false));

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
                processDiffForBuggyness(diff, parentOfFix, newFileContentsInFix, injectedVersion, fixedVersion, fixCommit, allProjectMethods);
            }
        } catch (IOException | GitAPIException e) {
            LOGGER.log(Level.SEVERE, "Error analyzing fix commit {0}: {1}", new Object[]{fixCommit.getName(), e.getMessage()});
        }
    }

    private void processDiffForBuggyness(DiffEntry diff, RevCommit parent, Map<String, String> newContents,
                                         Release iv, Release fv, RevCommit fc, List<JavaMethod> methods) throws IOException {
        String filePath = diff.getNewPath();
        if (!filePath.endsWith(JAVA_EXTENSION) || filePath.contains(TEST_FOLDER)) {
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

            if (isActuallyChangedByFix(fixedMd, preFixMd)) {
                String fqn = filePath + "/" + signature;
                labelBuggyMethods(fqn, iv, fv, fc, methods);
            }
        }
    }

    private boolean isActuallyChangedByFix(MethodDeclaration fixedMd, MethodDeclaration preFixMd) {
        String hashFixed = GitUtils.calculateBodyHash(fixedMd);
        String hashPreFix = (preFixMd != null) ? GitUtils.calculateBodyHash(preFixMd) : null;

        return !Objects.equals(hashPreFix, hashFixed);
    }

    private void labelBuggyMethods(String fixedMethodFQN, Release injectedVersion, Release fixedVersion, RevCommit fixCommit, List<JavaMethod> allProjectMethods) {
        for (JavaMethod projectMethod : allProjectMethods) {
            if (projectMethod.getFullyQualifiedName().equals(fixedMethodFQN)) {
                // Add the fix commit if the method belongs to the FV and the commit has touched it
                if (projectMethod.getRelease().getId() == fixedVersion.getId() && projectMethod.getCommits().contains(fixCommit)) {
                    projectMethod.addFixCommit(fixCommit);
                }
                // Label as buggy if the method's release is between IV (included) and FV (excluded)
                if (projectMethod.getRelease().getId() >= injectedVersion.getId() &&
                        projectMethod.getRelease().getId() < fixedVersion.getId()) {
                    projectMethod.setBuggy(true);
                }
            }
        }
    }

    private void filterAndRenumberReleases() {
        this.fullReleaseList.removeIf(release -> release.getCommitList().isEmpty());

        int idCounter = 1;
        for (Release r : this.fullReleaseList) {
            r.setId(idCounter++);
        }
    }

}