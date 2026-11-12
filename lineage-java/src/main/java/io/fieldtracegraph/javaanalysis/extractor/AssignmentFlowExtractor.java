package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.flow.NodeFlowOwnership;

import java.util.Map;

// 作用：提取赋值语句产生的数据边。
// 使用场景：a = b、字段赋值与复合赋值。

public final class AssignmentFlowExtractor {
    private final ValueEdgeEmitter edgeEmitter = new ValueEdgeEmitter();

    // 作用：提取赋值流；逻辑：依次处理变量初始化、赋值语句与返回语句，并调用边发射器写图。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        int[] statementOrdinal = {0};
        for (VariableDeclarator variable : scope.callable().findAll(VariableDeclarator.class)) {
            if (!CallableOwnership.belongsToCallable(variable, scope.callable())
                    || NodeFlowOwnership.belongsToOuterExecutable(variable, scope.callable())) {
                continue;
            }
            variable.getInitializer().ifPresent(initializer ->
                    addEdges(
                            source,
                            scope,
                            nodes,
                            accumulator,
                            context,
                            initializer,
                            context.applyAlias(variable.getNameAsString()),
                            false,
                            initializer,
                            statementOrdinal[0]++
                    ));
        }
        for (AssignExpr assignment : scope.callable().findAll(AssignExpr.class)) {
            if (NodeFlowOwnership.belongsToOuterExecutable(assignment, scope.callable())) {
                continue;
            }
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(assignment, scope.callable())
                    && !CallableOwnership.isCapturedMemberWrite(assignment, scope.callable())) {
                continue;
            }
            addEdges(
                    source,
                    scope,
                    nodes,
                    accumulator,
                    context,
                    assignment.getValue(),
                    context.key(assignment.getTarget()),
                    assignment.getTarget() instanceof FieldAccessExpr,
                    assignment,
                    statementOrdinal[0]++
            );
        }
        for (ReturnStmt returnStmt : scope.callable().findAll(ReturnStmt.class)) {
            if (!CallableOwnership.belongsToCallable(returnStmt, scope.callable())
                    || NodeFlowOwnership.belongsToOuterExecutable(returnStmt, scope.callable())) {
                continue;
            }
            returnStmt.getExpression().ifPresent(expression ->
                    addEdges(
                            source,
                            scope,
                            nodes,
                            accumulator,
                            context,
                            expression,
                            "RETURN",
                            false,
                            expression,
                            statementOrdinal[0]++
                    ));
        }
    }

    // 作用：转发赋值边生成；逻辑：把来源、目标与位置交给统一边发射器处理。
    private void addEdges(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            Expression sourceExpression,
            String targetKey,
            boolean fieldTarget,
            Node node,
            int statementOrdinal
    ) {
        int ignoredOrdinal = edgeEmitter.emit(
                source, scope, nodes, accumulator, context,
                sourceExpression, targetKey, fieldTarget, node, statementOrdinal, null
        );
    }
}
