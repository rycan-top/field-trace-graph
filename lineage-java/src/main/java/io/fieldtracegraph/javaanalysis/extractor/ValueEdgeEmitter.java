package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.SwitchExpr;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.Provenance;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.JavaExpressionIdentity;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;

import java.util.Map;

// 作用：统一发射数据边并标注解析强度与证据来源。
// 使用场景：所有提取器产出结论时共用的写边入口。

/**
 * Persists an already-resolved expression flow into the graph. Extractors own
 * the rule that decides which target a value writes to; this class owns node
 * creation, evidence locations and duplicate-safe edge construction.
 */
final class ValueEdgeEmitter {
    private final ValueNodeCatalog nodeCatalog = new ValueNodeCatalog();

    // 作用：发射值边；逻辑：以源码来源为默认证据级别调用完整重载。
    int emit(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            Expression sourceExpression,
            String targetKey,
            boolean fieldTarget,
            Node anchor,
            int statementOrdinal,
            String operation
    ) {
        return emit(source, scope, nodes, accumulator, context, sourceExpression,
                targetKey, fieldTarget, anchor, statementOrdinal, operation,
                Provenance.SOURCE);
    }

    // 作用：发射值边；逻辑：按三元与 switch 拆分支，其余展开来源并逐条写入目标边。
    int emit(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            Expression sourceExpression,
            String targetKey,
            boolean fieldTarget,
            Node anchor,
            int statementOrdinal,
            String operation,
            Provenance provenance
    ) {
        if (targetKey == null || targetKey.isBlank()) {
            return statementOrdinal;
        }
        Expression expression = unwrap(sourceExpression);
        if (expression instanceof ConditionalExpr conditional) {
            return emitConditional(
                    source, scope, nodes, accumulator, context, conditional,
                    targetKey, fieldTarget, anchor, statementOrdinal, operation, provenance
            );
        }
        if (expression instanceof SwitchExpr switchExpression) {
            return emitSwitch(
                    source, scope, nodes, accumulator, context, switchExpression,
                    targetKey, fieldTarget, anchor, statementOrdinal, operation, provenance
            );
        }
        ValueNodeRecord target = nodeCatalog.ensure(
                source, scope, nodes, accumulator, targetKey, nodeKind(targetKey), anchor
        );
        int ordinal = statementOrdinal;
        for (ExpressionFlowSources.Source input : ExpressionFlowSources.collect(expression, context)) {
            if ("BUILDER_RESULT".equals(input.edgeType())) {
                // Builder property edges already target the concrete receiver
                // fields. Keeping the build() result edge would expose an
                // implementation-only CALL_RESULT as a business value.
                continue;
            }
            ValueNodeRecord origin = nodeCatalog.ensure(
                    source, scope, nodes, accumulator, input.key(), nodeKind(input.key()), sourceExpression
            );
            if (origin.valueId().equals(target.valueId())) {
                continue;
            }
            String edgeType = fieldTarget ? "FIELD_WRITE" : input.edgeType();
            String edgeOperation = operation == null || operation.isBlank()
                    ? expressionOperation(expression)
                    : operation;
            addEdge(source, scope, accumulator, origin, target, edgeType, edgeOperation,
                    anchor, ordinal++, provenance);
        }
        return ordinal;
    }

    // 作用：发射三元合并流；逻辑：创建 PHI 节点，分别接入真值与假值分支后连到目标。
    private int emitConditional(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            ConditionalExpr conditional,
            String targetKey,
            boolean fieldTarget,
            Node anchor,
            int statementOrdinal,
            String operation,
            Provenance provenance
    ) {
        int ordinal = statementOrdinal;
        String phiKey = "phi:" + targetKey + "@conditional:" + sourceKey(anchor)
                + ":" + ordinal;
        ValueNodeRecord phi = nodeCatalog.ensure(
                source, scope, nodes, accumulator, phiKey, "PHI", conditional
        );
        String condition = conditionText(conditional.getCondition());
        ordinal = emitConditionalBranch(
                source, scope, nodes, accumulator, context,
                conditional.getThenExpr(), phi, condition, fieldTarget, anchor, ordinal, provenance
        );
        ordinal = emitConditionalBranch(
                source, scope, nodes, accumulator, context,
                conditional.getElseExpr(), phi, negate(condition), fieldTarget, anchor, ordinal, provenance
        );
        ValueNodeRecord target = nodeCatalog.ensure(
                source, scope, nodes, accumulator, targetKey, nodeKind(targetKey), anchor
        );
        addEdge(
                source, scope, accumulator, phi, target,
                fieldTarget ? "FIELD_WRITE" : "ASSIGNED_FROM",
                operation == null || operation.isBlank() ? "conditional merge" : operation,
                anchor, ordinal++, provenance
        );
        return ordinal;
    }

    // 作用：发射三元单分支；逻辑：嵌套三元递归拆分，普通表达式按输入来源连到 PHI。
    private int emitConditionalBranch(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            Expression branchExpression,
            ValueNodeRecord phi,
            String condition,
            boolean fieldTarget,
            Node anchor,
            int statementOrdinal,
            Provenance provenance
    ) {
        Expression expression = unwrap(branchExpression);
        int ordinal = statementOrdinal;
        if (expression instanceof ConditionalExpr) {
            String branchKey = "branch:" + phi.canonicalValue() + "@" + sourceKey(anchor)
                    + ":" + ordinal;
            ordinal = emit(
                    source, scope, nodes, accumulator, context,
                    expression, branchKey, false, anchor, ordinal, condition, provenance
            );
            ValueNodeRecord branch = nodeCatalog.ensure(
                    source, scope, nodes, accumulator, branchKey, "EXPRESSION", expression
            );
            addEdge(source, scope, accumulator, branch, phi, "PHI_INPUT",
                    condition, anchor, ordinal++, provenance);
            return ordinal;
        }
        for (ExpressionFlowSources.Source input : ExpressionFlowSources.collect(expression, context)) {
            ValueNodeRecord origin = nodeCatalog.ensure(
                    source, scope, nodes, accumulator,
                    input.key(), nodeKind(input.key()), expression
            );
            addEdge(
                    source, scope, accumulator, origin, phi,
                    "PHI_INPUT",
                    condition, anchor, ordinal++, provenance
            );
        }
        return ordinal;
    }

    /**
     * Switch expressions are value joins. Each arm contributes a PHI input
     * gated by its case label, and the join feeds the assignment target. This
     * keeps a branch-specific field origin visible instead of flattening all
     * arms into one anonymous expression edge.
     */
    // 作用：发射 switch 合并流；逻辑：按分支标签与守卫收集各分支输入到 PHI，再连到目标。
    private int emitSwitch(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            SwitchExpr switchExpression,
            String targetKey,
            boolean fieldTarget,
            Node anchor,
            int statementOrdinal,
            String operation,
            Provenance provenance
    ) {
        int ordinal = statementOrdinal;
        String selector = conditionText(switchExpression.getSelector());
        String phiKey = "phi:" + targetKey + "@switch:" + sourceKey(anchor)
                + ":" + ordinal;
        ValueNodeRecord phi = nodeCatalog.ensure(
                source, scope, nodes, accumulator, phiKey, "PHI", switchExpression
        );
        for (var entry : switchExpression.getEntries()) {
            String condition;
            if (entry.getLabels().isEmpty()) {
                condition = "default(" + selector + ")";
            } else {
                String labels = entry.getLabels().stream()
                        .map(label -> selector + " == " + conditionText(label))
                        .reduce((left, right) -> left + " || " + right)
                        .orElse(selector);
                condition = entry.getLabels().size() > 1 ? "(" + labels + ")" : labels;
            }
            if (entry.getGuard().isPresent()) {
                condition = condition + " && " + conditionText(entry.getGuard().orElseThrow());
            }
            for (Expression armValue : ExpressionFlowSources.switchEntryValues(entry)) {
                var inputs = ExpressionFlowSources.collect(armValue, context);
                if (inputs.isEmpty()) {
                    ValueNodeRecord origin = nodeCatalog.ensure(
                            source, scope, nodes, accumulator,
                            context.key(armValue), nodeKind(context.key(armValue)), armValue
                    );
                    addEdge(source, scope, accumulator, origin, phi,
                            "PHI_INPUT", condition, anchor, ordinal++, provenance);
                    continue;
                }
                for (ExpressionFlowSources.Source input : inputs) {
                    ValueNodeRecord origin = nodeCatalog.ensure(
                            source, scope, nodes, accumulator,
                            input.key(), nodeKind(input.key()), armValue
                    );
                    addEdge(source, scope, accumulator, origin, phi,
                            "PHI_INPUT", condition, anchor, ordinal++, provenance);
                }
            }
        }
        ValueNodeRecord target = nodeCatalog.ensure(
                source, scope, nodes, accumulator, targetKey, nodeKind(targetKey), anchor
        );
        addEdge(
                source, scope, accumulator, phi, target,
                fieldTarget ? "FIELD_WRITE" : "ASSIGNED_FROM",
                operation == null || operation.isBlank() ? "switch merge" : operation,
                anchor, ordinal++, provenance
        );
        return ordinal;
    }

    // 作用：写入值边；逻辑：以源码来源为默认证据级别调用完整重载。
    void addEdge(
            SourceFileRecord source,
            CallableScope scope,
            JavaAnalysisAccumulator accumulator,
            ValueNodeRecord origin,
            ValueNodeRecord target,
            String edgeType,
            String operation,
            Node anchor,
            int statementOrdinal
    ) {
        addEdge(source, scope, accumulator, origin, target, edgeType, operation,
                anchor, statementOrdinal, Provenance.SOURCE);
    }

    // 作用：写入值边；逻辑：跳过自环，生成证据 ID 后追加带位置与来源级别的数据边。
    void addEdge(
            SourceFileRecord source,
            CallableScope scope,
            JavaAnalysisAccumulator accumulator,
            ValueNodeRecord origin,
            ValueNodeRecord target,
            String edgeType,
            String operation,
            Node anchor,
            int statementOrdinal,
            Provenance provenance
    ) {
        if (origin.valueId().equals(target.valueId())) {
            return;
        }
        int line = anchor.getBegin().map(position -> position.line).orElse(1);
        int column = anchor.getBegin().map(position -> position.column).orElse(1);
        int endLine = anchor.getEnd().map(position -> position.line).orElse(line);
        int endColumn = anchor.getEnd().map(position -> position.column).orElse(column);
        String evidenceId = Ids.sha256(
                scope.symbolId(), origin.valueId(), target.valueId(), edgeType,
                String.valueOf(line), String.valueOf(column)
        );
        accumulator.addDataEdge(new DataEdgeRecord(
                Ids.sha256(scope.symbolId(), origin.valueId(), target.valueId(), edgeType,
                        String.valueOf(line), String.valueOf(column), String.valueOf(statementOrdinal)),
                scope.symbolId(),
                origin.valueId(),
                target.valueId(),
                edgeType,
                operation,
                "EXACT_STATIC",
                evidenceId,
                null,
                null,
                source.fileId(),
                line,
                column,
                endLine,
                endColumn,
                statementOrdinal,
                provenance.name()
        ));
    }

    // 作用：确保节点存在；逻辑：按键推断种类后委托节点目录创建。
    ValueNodeRecord ensure(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            String key,
            Node anchor
    ) {
        return nodeCatalog.ensure(source, scope, nodes, accumulator, key, nodeKind(key), anchor);
    }

    // 作用：去除表达式包装；逻辑：循环剥离括号与强制类型转换。
    private Expression unwrap(Expression expression) {
        Expression value = expression;
        while (value instanceof com.github.javaparser.ast.expr.EnclosedExpr enclosed
                || value instanceof com.github.javaparser.ast.expr.CastExpr) {
            value = value instanceof com.github.javaparser.ast.expr.EnclosedExpr enclosed
                    ? enclosed.getInner()
                    : ((com.github.javaparser.ast.expr.CastExpr) value).getExpression();
        }
        return value;
    }

    // 作用：生成表达式操作描述；逻辑：仅二元与一元表达式返回规范化文本。
    private String expressionOperation(Expression expression) {
        if (expression instanceof BinaryExpr || expression instanceof com.github.javaparser.ast.expr.UnaryExpr) {
            return JavaExpressionIdentity.normalizedExpression(expression);
        }
        return null;
    }

    // 作用：格式化条件文本；逻辑：规范化后为空时使用占位。
    private String conditionText(Expression expression) {
        String text = expression == null
                ? ""
                : JavaExpressionIdentity.normalizedExpression(expression);
        return text.isBlank() ? "condition" : text;
    }

    // 作用：取反条件；逻辑：用括号包裹后前置非运算。
    private String negate(String condition) {
        return "!(" + condition + ")";
    }

    // 作用：生成位置键；逻辑：拼接节点起始行与列。
    private String sourceKey(Node anchor) {
        int line = anchor.getBegin().map(position -> position.line).orElse(1);
        int column = anchor.getBegin().map(position -> position.column).orElse(1);
        return line + ":" + column;
    }

    // 作用：推断节点种类；逻辑：按逻辑前缀、字面量、容器元素、调用结果与字段路径分类。
    private String nodeKind(String key) {
        if (key.startsWith("field-write:")) {
            return "FIELD";
        }
        if (key.startsWith("branch:field-write:")) {
            return "FIELD";
        }
        if (key.startsWith("phi:")) {
            return "PHI";
        }
        if (key.startsWith("branch:")) {
            return "EXPRESSION";
        }
        if (key.startsWith("before:")) {
            return "EXPRESSION";
        }
        if (key.startsWith("method-ref:")) {
            return "EXPRESSION";
        }
        if (key.startsWith("reference:")) {
            return "EXPRESSION";
        }
        if (key.startsWith("literal:")) {
            return "CONSTANT";
        }
        if (key.contains("[*]") || key.matches(".*\\[[^]]+].*")) {
            return "CONTAINER_ELEMENT";
        }
        if (key.startsWith("call:") || key.startsWith("new:")) {
            return "CALL_RESULT";
        }
        if (key.contains(".")) {
            return "FIELD";
        }
        return "EXPRESSION";
    }
}
