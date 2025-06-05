package model;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

public class BranchDecisionCounterVisitor extends VoidVisitorAdapter<Void> {
    private int count = 0;

    private void countLogicalOperatorsInCondition(Node conditionNode) {
        if (conditionNode instanceof BinaryExpr) {
            BinaryExpr bn = (BinaryExpr) conditionNode;
            if (bn.getOperator() == BinaryExpr.Operator.AND || bn.getOperator() == BinaryExpr.Operator.OR) {
                count++;
                countLogicalOperatorsInCondition(bn.getLeft());
                countLogicalOperatorsInCondition(bn.getRight());
            }
        }
    }

    @Override
    public void visit(IfStmt n, Void arg) {
        count++;
        countLogicalOperatorsInCondition(n.getCondition());
        super.visit(n, arg); // Visita il corpo e l'eventuale 'else'
    }

    @Override
    public void visit(ForStmt n, Void arg) {
        count++;
        n.getCompare().ifPresent(this::countLogicalOperatorsInCondition);
        super.visit(n, arg);
    }

    @Override
    public void visit(ForEachStmt n, Void arg) {
        count++;
        super.visit(n, arg);
    }

    @Override
    public void visit(WhileStmt n, Void arg) {
        count++;
        countLogicalOperatorsInCondition(n.getCondition());
        super.visit(n, arg);
    }

    @Override
    public void visit(DoStmt n, Void arg) {
        count++;
        countLogicalOperatorsInCondition(n.getCondition());
        super.visit(n, arg);
    }

    @Override
    public void visit(SwitchEntry n, Void arg) {
        if (!n.getLabels().isEmpty()) {
            count++;
        }
        super.visit(n, arg);
    }

    @Override
    public void visit(ConditionalExpr n, Void arg) {
        count++;
        countLogicalOperatorsInCondition(n.getCondition());
        super.visit(n, arg);
    }

    @Override
    public void visit(CatchClause n, Void arg) {
        count++;
        super.visit(n, arg);
    }

    public int getCount() {
        return count;
    }
}
