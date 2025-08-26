package model;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class JavaMethod {

    private String fullyQualifiedName; // Es: com/example/MyClass.java/myMethod(int,String)
    private Release release;

    private String bodyHash;

    private List<RevCommit> commits; // Commits that change the method
    private List<RevCommit> fixCommits; // Commits that fixed the method
    private boolean buggy;

    //metrics
    private int loc;
    private int numParameters;
    private int numAuthors;
    private int numRevisions;
    private int totalStmtAdded;
    private int totalStmtDeleted;

    private int numberOfBranches;
    private int nestingDepth;
    private int numberOfCodeSmells;
    private int maxChurn;

    private int nFix;

    public JavaMethod(String fullyQualifiedName, Release release) {
        this.fullyQualifiedName = fullyQualifiedName;
        this.release = release;
        this.commits = new ArrayList<>();
        this.fixCommits = new ArrayList<>();
        this.buggy = false;

        this.loc = 0;
        this.numRevisions = 0;
        this.numAuthors = 0;
        this.totalStmtAdded = 0;
        this.totalStmtDeleted = 0;
        this.numberOfBranches = 0;
        this.nestingDepth = 0;
        this.numberOfCodeSmells = 0;
        this.maxChurn = 0;
        this.nFix = 0;
    }

    public int getLoc() {
        return loc;
    }

    public void setLoc(int loc) {
        this.loc = loc;
    }

    public boolean isBuggy() {
        return buggy;
    }

    public int getNumParameters() {
        return numParameters;
    }

    public void setNumParameters(int numParameters) {
        this.numParameters = numParameters;
    }

    public void setBuggy(boolean buggy) {
        this.buggy = buggy;
    }

    public Release getRelease() {
        return release;
    }

    public void setRelease(Release release) {
        this.release = release;
    }

    public String getFullyQualifiedName() {
        return fullyQualifiedName;
    }

    public void setFullyQualifiedName(String fullyQualifiedName) {
        this.fullyQualifiedName = fullyQualifiedName;
    }

    public void addCommit(RevCommit commit) {
        commits.add(commit);
    }

    public void addFixCommit(RevCommit commit) {
        fixCommits.add(commit);
    }


    public static String getSignature(MethodDeclaration md) {
        return md.getSignature().asString();
    }

    public List<RevCommit> getFixCommits() {
        return fixCommits;
    }

    public List<RevCommit> getCommits() {
        return commits;
    }

    public int getNumAuthors() {
        return numAuthors;
    }

    public void setNumAuthors(int numAuthors) {
        this.numAuthors = numAuthors;
    }

    public void incrementNumRevisions() {
        this.numRevisions++;
    }

    public int getNumRevisions() {
        return numRevisions;
    }

    public void setNumRevisions(int numRevisions) {
        this.numRevisions = numRevisions;
    }

    public int getTotalStmtAdded() {
        return totalStmtAdded;
    }

    public void setTotalStmtAdded(int totalStmtAdded) {
        this.totalStmtAdded = totalStmtAdded;
    }

    public int getTotalStmtDeleted() {
        return totalStmtDeleted;
    }

    public void setTotalStmtDeleted(int totalStmtDeleted) {
        this.totalStmtDeleted = totalStmtDeleted;
    }

    public void addStmtAdded(int count) { this.totalStmtAdded += count; }

    public void addStmtDeleted(int count) { this.totalStmtDeleted += count; }

    public String getBodyHash() {
        return bodyHash;
    }

    public void setBodyHash(String bodyHash) {
        this.bodyHash = bodyHash;
    }

    public int getNumberOfBranches() {
        return numberOfBranches;
    }

    public void setNumberOfBranches(int numberOfBranches) {
        this.numberOfBranches = numberOfBranches;
    }

    public int getNestingDepth() {
        return nestingDepth;
    }

    public void setNestingDepth(int nestingDepth) {
        this.nestingDepth = nestingDepth;
    }

    public int getNumberOfCodeSmells() {
        return numberOfCodeSmells;
    }

    public void setNumberOfCodeSmells(int numberOfCodeSmells) {
        this.numberOfCodeSmells = numberOfCodeSmells;
    }

    public double getAvgChurn() {
        if (numRevisions == 0) {
            return 0.0;
        }
        return (double) (totalStmtAdded + totalStmtDeleted) / numRevisions;
    }

    public int getMaxChurnInARevision() { return this.maxChurn; }
    public void setMaxChurnInARevision(int maxChurn) {
        this.maxChurn = maxChurn;
    }

    public void updateMaxChurn(int churnOfThisRevision) {
        if (churnOfThisRevision > this.maxChurn) {
            this.maxChurn = churnOfThisRevision;
        }
    }

    public int getNFix() {
        return nFix;
    }

    public void setNFix(int nFix) {
        this.nFix = nFix;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JavaMethod that = (JavaMethod) o;
        return Objects.equals(fullyQualifiedName, that.fullyQualifiedName) &&
                Objects.equals(release.getId(), that.release.getId()); // Un metodo è unico per nome E release
    }


    @Override
    public int hashCode() {
        return Objects.hash(fullyQualifiedName, release.getId());
    }

    @Override
    public String toString() {
        return "JavaMethod{" +
                "FQN='" + fullyQualifiedName + '\'' +
                ", release=" + release.getId() +
                ", buggy=" + buggy +
                '}';
    }
}

