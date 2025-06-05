package controller;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;

import model.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.InitCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import utils.GitUtils;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;


public class ExtractFromGit {
    private List<Ticket> ticketList;
    private List<Release> releaseList; // first 34% of releases
    private List<Release> fullReleaseList;
    private List<RevCommit> commitList;

    private Git git;
    private Repository repository;
    private JavaParser javaParser;

    public ExtractFromGit(String projectName, List<Release> allReleases, List<Ticket> ticketList) throws IOException {

        ParserConfiguration parserConfiguration = new ParserConfiguration();
        this.javaParser = new JavaParser(parserConfiguration);

        InitCommand init = Git.init();

        File repoDir = new File("/Users/saramalaspina/Desktop/" + projectName.toLowerCase() + "_isw2");
        File gitDir = new File(repoDir, ".git");

        if (!gitDir.exists()) {
            System.err.println("Error: directory .git not found in" + repoDir.getAbsolutePath());
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

    public List<Ticket> getTicketList() {
        return ticketList;
    }

    public List<Release> getReleaseList() {
        return releaseList;
    }

    public List<Release> getFullReleaseList() {
        return fullReleaseList;
    }

    public void setTicketList(List<Ticket> ticketList) {
        this.ticketList = ticketList;
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
            System.err.println("Ticket list non inizializzata.");
            return null;
        }

        if (!commitList.isEmpty()) {
            return commitList;
        }

        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
        Iterable<RevCommit> commitsIterable = git.log().all().call();
        commitsIterable.forEach(commitList::add);
        // sort the commits from the latest to the newest
        commitList.sort(Comparator.comparing(c -> c.getCommitterIdent().getWhen()));

        for (RevCommit commit : commitList) {
            LocalDate commitDate = LocalDate.parse(formatter.format(commit.getCommitterIdent().getWhen()));
            LocalDate lowerBoundDate = LocalDate.parse(formatter.format(new Date(0)));

            // assign commits to release
            for (Release release : this.fullReleaseList) {
                LocalDate releaseDate = release.getDate();
                if (!commitDate.isBefore(lowerBoundDate) && !commitDate.isAfter(releaseDate)) {
                    release.addCommit(commit);
                }
                lowerBoundDate = releaseDate;
            }
        }

        setReleaseListForAnalysis();

        return commitList;
    }

    public List<RevCommit> filterCommitsAndSetToTicket() {

        List<RevCommit> filteredCommits = new ArrayList<>();
        if (commitList.isEmpty()) {
            System.err.println("Lista commit vuota. Chiamare prima getAllCommitsAndAssignToReleases().");
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
        Set<String> processedMethodsForRelease = new HashSet<>(); // Per evitare duplicati FQN per singola release

        for (Release release : this.releaseList) { // Solo release per analisi
            processedMethodsForRelease.clear();
            List<RevCommit> releaseCommits = release.getCommitList();
            if (releaseCommits.isEmpty()) continue;

            // Prendi l'ultimo commit della release per avere lo snapshot dei file
            // Assumendo che i commit in release.getCommitList() siano ordinati per data
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
                                    currentReleaseMethod.setBodyHash(calculateBodyHash(md));

                                    // Calculate Metrics
                                    int currentLoc = calculateLOC(md);
                                    currentReleaseMethod.setLoc(currentLoc);
                                    currentReleaseMethod.setNumParameters(md.getParameters().size());
                                    currentReleaseMethod.setNumberOfBranches(calculateNumberOfBranches(md));
                                    currentReleaseMethod.setNestingDepth(calculateNestingDepth(md));
                                    allMethodsOfReleases.add(currentReleaseMethod);
                                    release.addMethod(currentReleaseMethod); // Associa il metodo alla sua release
                                    currentReleaseMethod.setBodyHash(calculateBodyHash(md));
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

        return allMethodsOfReleases;
    }

    private String calculateBodyHash(MethodDeclaration md) {
        if (md == null) return null;
        String normalizedBody = normalizeMethodBody(md);
        if (normalizedBody.isEmpty()) return "EMPTY_BODY_HASH"; // O un altro placeholder
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(normalizedBody.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(encodedhash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 Hashing error", e);
        }
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private String normalizeMethodBody(MethodDeclaration md) {
        if (md == null || !md.getBody().isPresent()) {
            return "";
        }
        // Rimuovi commenti, spazi bianchi eccessivi, ecc.
        // Questo è un esempio MOLTO SEMPLICE. Una normalizzazione robusta è complessa.
        String body = md.getBody().get().toString();
        body = body.replaceAll("//.*|/\\*(?s:.*?)\\*/", ""); // Rimuovi commenti
        body = body.replaceAll("\\s+", " "); // Sostituisci spazi multipli con uno singolo
        return body.trim();
    }

    public void addCommitsToMethods(List<JavaMethod> allMethods, List<RevCommit> commitListInput) throws IOException, GitAPIException {
        List<RevCommit> sortedCommits = new ArrayList<>(commitListInput);
        sortedCommits.sort(Comparator.comparing(RevCommit::getCommitTime));

        for (RevCommit commit : sortedCommits) {
            if (commit.getParentCount() == 0) {
                continue;
            }

            RevCommit parent = commit.getParent(0);
            List<DiffEntry> diffs = getDiffEntries(parent, commit);

            Map<String, String> oldFileContents = getFileContents(parent, diffs, true);
            Map<String, String> newFileContents = getFileContents(commit, diffs, false);

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

                Map<String, MethodDeclaration> oldMethods = parseMethods(oldContent);
                Map<String, MethodDeclaration> newMethods = parseMethods(newContent);

                for (Map.Entry<String, MethodDeclaration> newMethodEntry : newMethods.entrySet()) {
                    String signature = newMethodEntry.getKey();
                    MethodDeclaration newMd = newMethodEntry.getValue();
                    MethodDeclaration oldMd = oldMethods.get(signature);

                    String newBodyHash = calculateBodyHash(newMd);
                    String oldBodyHash = (oldMd != null) ? calculateBodyHash(oldMd) : null;

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

        // Calcola NumAuthors dopo che tutti i commit sono stati processati
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


    private List<DiffEntry> getDiffEntries(RevCommit parent, RevCommit commit) throws IOException, GitAPIException {
        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(repository);
            diffFormatter.setDiffComparator(RawTextComparator.DEFAULT);
            diffFormatter.setContext(0); // Nessuna linea di contesto, solo le differenze
            return diffFormatter.scan(parent.getTree(), commit.getTree());
        }
    }

    private Map<String, String> getFileContents(RevCommit commit, List<DiffEntry> diffs, boolean useOldPath) throws IOException {
        Map<String, String> contents = new HashMap<>();
        try (ObjectReader reader = repository.newObjectReader()) {
            for (DiffEntry diff : diffs) {
                String path = useOldPath ? diff.getOldPath() : diff.getNewPath();
                ObjectId id = useOldPath ? diff.getOldId().toObjectId() : diff.getNewId().toObjectId();

                if (DiffEntry.DEV_NULL.equals(path)) continue; // Skip /dev/null

                try {
                    ObjectLoader loader = reader.open(id);
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    loader.copyTo(output);
                    contents.put(path, output.toString());
                } catch (org.eclipse.jgit.errors.MissingObjectException e) {
                    // Oggetto non trovato, potrebbe essere un file binario o un problema
                    System.err.println("Missing object: " + id + " for path " + path + " in commit " + commit.getName());
                }
            }
        }
        return contents;
    }

    private void updateMethodMetricsForCommit(List<JavaMethod> allProjectMethods, String filePath,
                                              MethodDeclaration currentMdAst_in_commit, RevCommit commit,
                                              MethodDeclaration oldMdAst_in_parent_commit, MethodDeclaration newMdAst_in_commit) {
        String signature = JavaMethod.getSignature(currentMdAst_in_commit);
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

                int locNewInCommit = calculateLOC(newMdAst_in_commit);

                if (oldMdAst_in_parent_commit != null) {
                    int locOldInParentCommit = calculateLOC(oldMdAst_in_parent_commit);

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


    private Map<String, MethodDeclaration> parseMethods(String content) {
        Map<String, MethodDeclaration> methods = new HashMap<>();
        if (content == null || content.isEmpty()) return methods;
        try {
            CompilationUnit cu = StaticJavaParser.parse(content);
            cu.findAll(MethodDeclaration.class).forEach(md -> {
                methods.put(JavaMethod.getSignature(md), md);
            });
        } catch (ParseProblemException | StackOverflowError ignored) {
        }
        return methods;
    }


    private int calculateLOC(MethodDeclaration md) {
        if (!md.getBody().isPresent()) {
            return 0;
        }
        String body = md.getBody().get().toString();
        if (body.startsWith("/*") && body.contains("*/")) {
            body = body.substring(body.indexOf("*/") + 2);
        }

        String[] lines = body.split("\r\n|\r|\n");
        long count = Arrays.stream(lines)
                .map(String::trim)
                .filter(line -> !line.isEmpty() &&
                        !line.startsWith("//") &&
                        !(line.startsWith("/*") && line.endsWith("*/")) &&
                        !line.equals("{") && !line.equals("}")
                ).count();

        if (count == 0 && lines.length > 2) {
            boolean allCommentsOrEmpty = true;
            for(String line : lines){
                String trimmedLine = line.trim();
                if(!trimmedLine.isEmpty() && !trimmedLine.startsWith("//") && !trimmedLine.startsWith("/*") && !trimmedLine.endsWith("*/") && !trimmedLine.equals("{") && !trimmedLine.equals("}")){
                    allCommentsOrEmpty = false;
                    break;
                }
            }
            if(allCommentsOrEmpty) return 0;
        }

        return (int) count;
    }

    private int calculateNumberOfBranches(MethodDeclaration md) {
        if (!md.getBody().isPresent()) {
            return 0;
        }
        BranchDecisionCounterVisitor visitor = new BranchDecisionCounterVisitor();
        md.getBody().get().accept(visitor, null);
        return visitor.getCount();
    }

    private int calculateNestingDepth(MethodDeclaration md) {
        if (!md.getBody().isPresent()) {
            return 0;
        }
        NestingDepthVisitor visitor = new NestingDepthVisitor();
        md.getBody().get().accept(visitor, null);
        return visitor.getMaxDepth();
    }


    public void setMethodBuggyness(List<JavaMethod> allProjectMethods) {
        if (this.ticketList == null) {
            System.err.println("Ticket list non inizializzata.");
            return;
        }

        for (JavaMethod projectMethod : allProjectMethods) {
            projectMethod.setBuggy(false); // Reset iniziale
        }

        for (Ticket ticket : this.ticketList) {
            Release injectedVersion = ticket.getIv();
            if (injectedVersion == null) continue; // IV non definito per questo ticket

            for (RevCommit fixCommit : ticket.getCommitList()) {
                Release fixedVersion = GitUtils.getReleaseOfCommit(fixCommit, this.fullReleaseList);
                if (fixedVersion == null) continue; // Commit di fix non appartiene a una release tracciata

                try {
                    if (fixCommit.getParentCount() == 0) continue;
                    RevCommit parentOfFix = fixCommit.getParent(0);
                    List<DiffEntry> diffs = getDiffEntries(parentOfFix, fixCommit);

                    Map<String, String> newFileContentsInFix = getFileContents(fixCommit, diffs, false);

                    for (DiffEntry diff : diffs) {
                        String filePath = diff.getNewPath();
                        if (!filePath.endsWith(".java") || filePath.contains("/test/")) continue;

                        String newContent = newFileContentsInFix.getOrDefault(filePath, "");
                        Map<String, MethodDeclaration> newMethodsInFix = parseMethods(newContent);

                        // Per determinare quali metodi sono stati *effettivamente* modificati dal fix
                        String oldContentInFix = getFileContents(parentOfFix, Collections.singletonList(diff), true).getOrDefault(diff.getOldPath(), "");
                        Map<String, MethodDeclaration> oldMethodsInFix = parseMethods(oldContentInFix);


                        for (Map.Entry<String, MethodDeclaration> fixedMethodEntry : newMethodsInFix.entrySet()) {
                            String signature = fixedMethodEntry.getKey();
                            MethodDeclaration fixedMd = fixedMethodEntry.getValue();
                            MethodDeclaration preFixMd = oldMethodsInFix.get(signature);

                            String hashFixed = calculateBodyHash(fixedMd);
                            String hashPreFix = calculateBodyHash(preFixMd);

                            boolean actuallyChangedByFix = (preFixMd == null && fixedMd != null) ||
                                    (hashPreFix != null && hashFixed != null && !hashPreFix.equals(hashFixed)) ||
                                    (hashPreFix == null && hashFixed != null && preFixMd != null) ||
                                    (hashPreFix != null && hashFixed == null && fixedMd != null);

                            if (actuallyChangedByFix) {
                                String fqn = filePath + "/" + signature;
                                labelBuggyMethods(fqn, injectedVersion, fixedVersion, fixCommit, allProjectMethods);
                            }
                        }
                    }
                } catch (IOException | GitAPIException e) {
                    System.err.println("Errore durante l'analisi del commit di fix " + fixCommit.getName() + ": " + e.getMessage());
                }
            }
        }
    }

    private void labelBuggyMethods(String fixedMethodFQN, Release injectedVersion, Release fixedVersion, RevCommit fixCommit, List<JavaMethod> allProjectMethods) {
        for (JavaMethod projectMethod : allProjectMethods) {
            if (projectMethod.getFullyQualifiedName().equals(fixedMethodFQN)) {
                // Aggiungi il commit di fix se il metodo appartiene alla FV e il commit lo ha toccato
                if (projectMethod.getRelease().getId() == fixedVersion.getId() && projectMethod.getCommits().contains(fixCommit) ) { // Assumiamo che getCommits contenga tutti i commit che hanno toccato il metodo in quella release
                    projectMethod.addFixCommit(fixCommit);
                }
                // Etichetta come buggy se la release del metodo è tra IV (inclusa) e FV (esclusa)
                if (projectMethod.getRelease().getId() >= injectedVersion.getId() &&
                        projectMethod.getRelease().getId() < fixedVersion.getId()) {
                    projectMethod.setBuggy(true);
                }
            }
        }
    }
}
