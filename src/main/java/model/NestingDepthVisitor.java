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
        enterControlStructure();
        super.visit(n, arg);
        exitControlStructure();
    }

    @Override
    public void visit(TryStmt n, Void arg) {
        enterControlStructure();
        n.getTryBlock().accept(this, arg);
        for (CatchClause c : n.getCatchClauses()) {
            c.accept(this, arg);
        }
        n.getFinallyBlock().ifPresent(f -> f.accept(this, arg));
        exitControlStructure();
    }

    @Override
    public void visit(CatchClause n, Void arg) {
        enterControlStructure();
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

