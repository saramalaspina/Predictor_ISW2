package model;

import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

public class NestingDepthVisitor extends VoidVisitorAdapter<Void> {
    private int currentDepth = 0;
    private int maxDepth = 0;

    private void enterControlStructure() {
        currentDepth++;
        if (currentDepth > maxDepth) {
            maxDepth = currentDepth;
        }
    }

    private void exitControlStructure() {
        currentDepth--;
    }

    @Override
    public void visit(IfStmt n, Void arg) {
        enterControlStructure();
        super.visit(n, arg);
        exitControlStructure();
    }

    @Override
    public void visit(ForStmt n, Void arg) {
        enterControlStructure();
        super.visit(n, arg);
        exitControlStructure();
    }

    @Override
    public void visit(ForEachStmt n, Void arg) {
        enterControlStructure();
        super.visit(n, arg);
        exitControlStructure();
    }

    @Override
    public void visit(WhileStmt n, Void arg) {
        enterControlStructure();
        super.visit(n, arg);
        exitControlStructure();
    }

    @Override
    public void visit(DoStmt n, Void arg) {
        enterControlStructure();
        super.visit(n, arg);
        exitControlStructure();
    }

    @Override
    public void visit(SwitchStmt n, Void arg) {
        enterControlStructure(); // Lo switch stesso è un livello
        super.visit(n, arg);
        exitControlStructure();
    }

    @Override
    public void visit(TryStmt n, Void arg) {
        enterControlStructure(); // Il blocco try è un livello
        n.getTryBlock().accept(this, arg); // Visita il corpo del try
        for (CatchClause c : n.getCatchClauses()) {
            c.accept(this, arg); // Visita ogni catch (enter/exit gestito nel visitor del catch se necessario)
        }
        n.getFinallyBlock().ifPresent(f -> f.accept(this, arg)); // Visita il finally
        exitControlStructure(); // Esce dal livello del try
    }

    @Override
    public void visit(CatchClause n, Void arg) {
        enterControlStructure(); // Il catch è un suo blocco di controllo
        super.visit(n, arg);
        exitControlStructure();
    }

    @Override
    public void visit(SynchronizedStmt n, Void arg) {
        enterControlStructure();
        super.visit(n, arg);
        exitControlStructure();
    }

    public int getMaxDepth() {
        return maxDepth;
    }
}

