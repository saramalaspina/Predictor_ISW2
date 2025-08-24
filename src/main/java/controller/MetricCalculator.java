package controller;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.*;
import model.*;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.*;

// Calculate features to create dataset
public class MetricCalculator {

    private MetricCalculator() {}

    public static int calculateLOC(MethodDeclaration md) {
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

    public static int calculateNumberOfBranches(MethodDeclaration md) {
        if (!md.getBody().isPresent()) {
            return 0;
        }
        BranchDecisionCounterVisitor visitor = new BranchDecisionCounterVisitor();
        md.getBody().get().accept(visitor, null);
        return visitor.getCount();
    }

    public static int calculateNestingDepth(MethodDeclaration md) {
        if (!md.getBody().isPresent()) {
            return 0;
        }
        NestingDepthVisitor visitor = new NestingDepthVisitor();
        md.getBody().get().accept(visitor, null);
        return visitor.getMaxDepth();
    }

    public static void calculateNFix(List<JavaMethod> allMethods, List<Ticket> ticketList, List<Release> releaseList) {
        // 1. Crea una mappa dei commit che sono "fix"
        //    Questo lo stai già facendo implicitamente, ma rendiamolo esplicito.
        Set<String> fixCommitNames = new HashSet<>();
        for (Ticket ticket : ticketList) {
            // Assicurati di considerare solo i ticket che sono bug risolti.
            // La tua query JIRA dovrebbe già farlo.
            for (RevCommit commit : ticket.getCommitList()) {
                fixCommitNames.add(commit.getName());
            }
        }

        // 2. Trova la data dell'ultimo commit per ogni release
        //    Questa informazione ci serve per sapere qual è il "momento dello snapshot".
        Map<Integer, Date> releaseSnapshotDate = new HashMap<>();
        for (Release release : releaseList) { // Usa la lista delle release in analisi
            if (!release.getCommitList().isEmpty()) {
                release.getCommitList().sort(Comparator.comparing(RevCommit::getCommitTime));
                Date lastCommitDate = release.getCommitList().get(release.getCommitList().size() - 1).getCommitterIdent().getWhen();
                releaseSnapshotDate.put(release.getId(), lastCommitDate);
            }
        }

        // 3. Itera su ogni metodo e calcola il suo NFix
        for (JavaMethod method : allMethods) {
            int nFixCount = 0;

            // Prendi la data dello snapshot della release del metodo
            Date snapshotDate = releaseSnapshotDate.get(method.getRelease().getId());
            if (snapshotDate == null) continue; // Salta se non abbiamo una data di snapshot

            // Itera su tutti i commit che hanno toccato questo metodo
            for (RevCommit commit : method.getCommits()) {

                // Un commit conta come "fix precedente" se:
                // 1. È un commit di fix (è nel nostro set).
                // 2. La sua data è ANTECEDENTE alla data dello snapshot del metodo.
                if (fixCommitNames.contains(commit.getName()) &&
                        commit.getCommitterIdent().getWhen().before(snapshotDate)) {

                    nFixCount++;
                }
            }
            method.setNFix(nFixCount);
        }
    }

    public static int calculateCodeSmells(MethodDeclaration md, int cyclomaticComplexity, int loc, int nestingDepth, int numParameters) {
        Optional<BlockStmt> bodyOpt = md.getBody();
        if (bodyOpt.isEmpty()) {
            return 0;
        }
        BlockStmt body = bodyOpt.get();
        int smellCount = 0;

        // 1. Long Method
        if (loc > 30) smellCount++;
        // 2. Complex Method
        if (cyclomaticComplexity > 7) smellCount++; // La tua soglia originale
        // 3. Deeply Nested
        if (nestingDepth > 4) smellCount++; // La tua soglia originale
        // 4. Long Parameter List
        if (numParameters > 4) smellCount++;

        // 5. Magic Number
        if (countMagicNumbers(body) > 1) smellCount++;
        // 6. Missing Default In Switch
        if (hasMissingDefaultInSwitch(body)) smellCount++;
        // 7. Empty Catch Block
        if (hasEmptyCatchBlock(body)) smellCount++;
        // 8. Returning Null
        if (isReturningNull(body)) smellCount++;
        // 9. Message Chain
        if (hasMessageChain(body)) smellCount++;

        return smellCount;
    }

    // --- Helper methods for code smells calculation ---

    private static long countMagicNumbers(BlockStmt body) {
        return body.findAll(IntegerLiteralExpr.class).stream()
                .filter(n -> {
                    try {
                        int val = n.asInt();
                        return val != 0 && val != 1 && val != -1;
                    } catch (Exception e) { return false; }
                })
                .filter(n -> n.getParentNode().map(p -> !(p instanceof VariableDeclarator)).orElse(true))
                .count();
    }

    private static boolean hasMissingDefaultInSwitch(BlockStmt body) {
        for (SwitchStmt switchStmt : body.findAll(SwitchStmt.class)) {
            if (switchStmt.getEntries().stream().noneMatch(entry -> entry.getLabels().isEmpty())) {
                return true; // Trovato uno switch senza default
            }
        }
        return false;
    }

    private static boolean hasEmptyCatchBlock(BlockStmt body) {
        for (CatchClause catchClause : body.findAll(CatchClause.class)) {
            if (catchClause.getBody().getStatements().isEmpty() && catchClause.getBody().getComment().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isReturningNull(BlockStmt body) {
        return body.findAll(ReturnStmt.class).stream()
                .anyMatch(r -> r.getExpression().isPresent() && r.getExpression().get() instanceof NullLiteralExpr);
    }

    private static boolean hasMessageChain(BlockStmt body) {
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            int chainLength = 0;
            Optional<com.github.javaparser.ast.expr.Expression> scope = call.getScope();
            while (scope.isPresent() && scope.get() instanceof MethodCallExpr) {
                chainLength++;
                scope = ((MethodCallExpr) scope.get()).getScope();
            }
            if (chainLength >= 3) {
                return true; // Trovata almeno una catena lunga
            }
        }
        return false;
    }
}