package io.fieldtracegraph.javaanalysis.extractor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.MethodSummaryRecord;
import io.fieldtracegraph.core.model.ParameterMutationRecord;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.JavaExpressionIdentity;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.GuardConditionResolver;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.flow.NodeFlowOwnership;
import io.fieldtracegraph.javaanalysis.resolution.AccessorResolver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 作用：生成方法级摘要。
// 使用场景：methods 命令与索引概览展示。

public final class MethodSummaryExtractor {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AccessorResolver accessorResolver = new AccessorResolver();
    private final ValueNodeCatalog nodeCatalog = new ValueNodeCatalog();

    // 作用：生成方法摘要；逻辑：汇总参数、字段写入与参数变更，并按返回分支逐条登记返回值摘要。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            TypeDeclaration<?> type,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        List<Map<String, String>> parameters = new ArrayList<>();
        for (var parameter : scope.callable().getParameters()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", parameter.getNameAsString());
            entry.put("type", parameter.getType().asString());
            ValueNodeRecord value = nodes.get(parameter.getNameAsString());
            if (value != null) {
                entry.put("value_id", value.valueId());
            }
            parameters.add(entry);
        }
        String parametersJson = json(parameters);
        List<FieldWriteSummary> fieldWriteSummaries =
                fieldWrites(source, scope, type, nodes, accumulator, context);
        String fieldWritesJson = json(fieldWriteSummaries);
        List<ParameterMutationRecord> parameterMutations =
                parameterMutations(source, scope, nodes, accumulator, fieldWriteSummaries, context);

        List<ReturnStmt> returns = scope.callable().findAll(ReturnStmt.class).stream()
                .filter(returnStmt -> NodeFlowOwnership.belongsToOuterExecutable(returnStmt, scope.callable()))
                .toList();
        if (returns.isEmpty()) {
            addSummary(source, scope, accumulator, parametersJson, null, 0, null,
                    fieldWritesJson, parameterMutations, scope.callable());
            return;
        }
        GuardConditionResolver guards = new GuardConditionResolver(scope.callable(), context);
        int returnOrder = 1;
        for (ReturnStmt returnStmt : returns) {
            if (returnStmt.getExpression().isEmpty()) {
                addSummary(source, scope, accumulator, parametersJson, null,
                        returnOrder++, guards.conditionFor(returnStmt), fieldWritesJson,
                        parameterMutations, returnStmt);
                continue;
            }
            List<ReturnLeaf> leaves = new ArrayList<>();
            collectReturnLeaves(returnStmt.getExpression().orElseThrow(),
                    guards.conditionFor(returnStmt), guards, leaves);
            for (ReturnLeaf leaf : leaves) {
                String returnValueId = valueId(
                        source, scope, nodes, accumulator, context, leaf.expression()
                );
                addSummary(source, scope, accumulator, parametersJson, returnValueId,
                        returnOrder++, leaf.condition(), fieldWritesJson,
                        parameterMutations, returnStmt);
            }
        }
    }

    // 作用：解析表达式值 ID；逻辑：取逻辑键并确保节点存在，失败时返回 null。
    private String valueId(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            Expression expression
    ) {
        String key = context.key(expression);
        if (key == null || key.isBlank()) {
            return null;
        }
        return nodeCatalog.ensure(
                source,
                scope,
                nodes,
                accumulator,
                key,
                JavaExpressionIdentity.nodeKind(key, scope.callable()),
                expression
        ).valueId();
    }

    // 作用：展开返回叶子；逻辑：递归拆解三元与 switch，把每个可能返回值和对应条件记入列表。
    private void collectReturnLeaves(
            Expression expression,
            String condition,
            GuardConditionResolver guards,
            List<ReturnLeaf> leaves
    ) {
        Expression value = unwrap(expression);
        if (value instanceof ConditionalExpr conditional) {
            String local = guards.conditionText(conditional.getCondition());
            collectReturnLeaves(
                    conditional.getThenExpr(),
                    guards.combine(condition, local),
                    guards,
                    leaves
            );
            collectReturnLeaves(
                    conditional.getElseExpr(),
                    guards.combine(condition, guards.negate(local)),
                    guards,
                    leaves
            );
            return;
        }
        if (value instanceof SwitchExpr switchExpression) {
            boolean hasValue = false;
            for (SwitchEntry entry : switchExpression.getEntries()) {
                List<Expression> armValues = ExpressionFlowSources.switchEntryValues(entry);
                if (armValues.isEmpty()) {
                    continue;
                }
                hasValue = true;
                String armCondition = switchArmCondition(
                        switchExpression, entry, condition, guards
                );
                for (Expression armValue : armValues) {
                    collectReturnLeaves(armValue, armCondition, guards, leaves);
                }
            }
            if (!hasValue) {
                leaves.add(new ReturnLeaf(switchExpression, condition));
            }
            return;
        }
        leaves.add(new ReturnLeaf(value, condition));
    }

    // 作用：构造 switch 分支条件；逻辑：组合选择器、标签、默认分支、守卫与上层条件。
    private String switchArmCondition(
            SwitchExpr switchExpression,
            SwitchEntry entry,
            String outerCondition,
            GuardConditionResolver guards
    ) {
        String selector = guards.conditionText(switchExpression.getSelector());
        String local;
        if (entry.getLabels().isEmpty()) {
            local = "default(" + selector + ")";
        } else {
            String labels = entry.getLabels().stream()
                    .map(label -> selector + " == " + guards.conditionText(label))
                    .reduce((left, right) -> left + " || " + right)
                    .orElse(selector);
            local = entry.getLabels().size() > 1 ? "(" + labels + ")" : labels;
        }
        if (entry.getGuard().isPresent()) {
            local = guards.combine(
                    local, guards.conditionText(entry.getGuard().orElseThrow())
            );
        }
        return guards.combine(outerCondition, local);
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

    // 作用：登记方法摘要；逻辑：按返回序号生成 ID，并写入参数、返回值、条件与位置。
    private void addSummary(
            SourceFileRecord source,
            CallableScope scope,
            JavaAnalysisAccumulator accumulator,
            String parametersJson,
            String returnValueId,
            int returnOrder,
            String branchCondition,
            String fieldWritesJson,
            List<ParameterMutationRecord> parameterMutations,
            Node evidence
    ) {
        String summaryId = Ids.sha256(scope.symbolId(), "summary", String.valueOf(returnOrder));
        accumulator.addMethodSummary(new MethodSummaryRecord(
                summaryId,
                scope.symbolId(),
                source.fileId(),
                parametersJson,
                returnValueId,
                returnOrder,
                branchCondition,
                fieldWritesJson,
                List.copyOf(parameterMutations),
                evidence.getBegin().map(position -> position.line).orElse(1),
                evidence.getBegin().map(position -> position.column).orElse(1),
                evidence.getEnd().map(position -> position.line).orElse(1),
                evidence.getEnd().map(position -> position.column).orElse(1)
        ));
    }

    // 作用：汇总参数变更；逻辑：从字段写入与容器写入中匹配参数根，生成去重后的变更记录。
    private List<ParameterMutationRecord> parameterMutations(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            List<FieldWriteSummary> fieldWrites,
            JavaValueFlowContext context
    ) {
        Map<String, Integer> parameterIndexes = new LinkedHashMap<>();
        var parameters = scope.callable().getParameters();
        for (int index = 0; index < parameters.size(); index++) {
            parameterIndexes.put(parameters.get(index).getNameAsString(), index);
        }
        Map<String, ParameterMutationRecord> mutations = new LinkedHashMap<>();
        for (FieldWriteSummary write : fieldWrites) {
            ParameterMatch match = parameterMatch(write.target(), parameterIndexes);
            if (match == null) {
                continue;
            }
            String operation = match.relativePath().contains("[")
                    ? "WRITE_ELEMENT_FIELD"
                    : "SETTER_WRITE";
            ParameterMutationRecord mutation = new ParameterMutationRecord(
                    match.parameterIndex(),
                    match.parameterName(),
                    match.relativePath(),
                    operation,
                    write.lineStart(),
                    write.columnStart(),
                    write.lineEnd(),
                    write.columnEnd()
            );
            mutations.putIfAbsent(mutationKey(mutation), mutation);
            if (operation.equals("WRITE_ELEMENT_FIELD")) {
                addParameterMutationEdge(
                        source,
                        scope,
                        nodes,
                        accumulator,
                        write,
                        match,
                        mutation
                );
            }
        }
        for (MethodCallExpr call : scope.callable().findAll(MethodCallExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())
                    || !context.isContainerMutation(call)
                    || call.getScope().isEmpty()) {
                continue;
            }
            String root = CallableOwnership.rootName(call.getScope().orElseThrow());
            Integer parameterIndex = root == null ? null : parameterIndexes.get(root);
            if (parameterIndex == null) {
                continue;
            }
            String target = context.mutationTarget(call);
            if (target == null) {
                target = context.bulkCopyTarget(call);
            }
            if (target == null || !target.startsWith(root)) {
                continue;
            }
            String relativePath = target.substring(root.length());
            String operation = containerMutationOperation(call);
            ParameterMutationRecord mutation = new ParameterMutationRecord(
                    parameterIndex,
                    root,
                    relativePath,
                    operation,
                    call.getBegin().map(position -> position.line).orElse(1),
                    call.getBegin().map(position -> position.column).orElse(1),
                    call.getEnd().map(position -> position.line)
                            .orElse(call.getBegin().map(position -> position.line).orElse(1)),
                    call.getEnd().map(position -> position.column)
                            .orElse(call.getBegin().map(position -> position.column).orElse(1))
            );
            mutations.putIfAbsent(mutationKey(mutation), mutation);
        }
        return List.copyOf(mutations.values());
    }

    // 作用：写参数变更边；逻辑：把参数节点连到被写入字段节点，并附带条件与位置证据。
    private void addParameterMutationEdge(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            FieldWriteSummary write,
            ParameterMatch match,
            ParameterMutationRecord mutation
    ) {
        ValueNodeRecord parameter = nodes.get(match.parameterName());
        if (parameter == null || write.targetValueId() == null) {
            return;
        }
        String evidenceId = Ids.sha256(
                scope.symbolId(),
                parameter.valueId(),
                write.targetValueId(),
                "MUTATES_PARAMETER",
                String.valueOf(mutation.lineStart()),
                String.valueOf(mutation.columnStart())
        );
        accumulator.addDataEdge(new DataEdgeRecord(
                Ids.sha256(evidenceId, "edge"),
                scope.symbolId(),
                parameter.valueId(),
                write.targetValueId(),
                "MUTATES_PARAMETER",
                "write element field " + mutation.targetPath(),
                "EXACT_STATIC",
                evidenceId,
                null,
                write.condition(),
                source.fileId(),
                mutation.lineStart(),
                mutation.columnStart(),
                mutation.lineEnd(),
                mutation.columnEnd(),
                mutation.lineStart() * 1_000 + mutation.columnStart()
        ));
    }

    // 作用：匹配写入目标所属参数；逻辑：目标等于参数名或以参数名加点或下标开头时返回索引与相对路径。
    private ParameterMatch parameterMatch(
            String target,
            Map<String, Integer> parameterIndexes
    ) {
        for (Map.Entry<String, Integer> entry : parameterIndexes.entrySet()) {
            String parameter = entry.getKey();
            if (target.equals(parameter)) {
                return new ParameterMatch(entry.getValue(), parameter, "");
            }
            if (target.startsWith(parameter + ".")
                    || target.startsWith(parameter + "[")) {
                return new ParameterMatch(
                        entry.getValue(),
                        parameter,
                        target.substring(parameter.length())
                );
            }
        }
        return null;
    }

    // 作用：判定容器变更操作；逻辑：按方法名映射为添加、写入或通用变更类型。
    private String containerMutationOperation(MethodCallExpr call) {
        return switch (call.getNameAsString()) {
            case "add", "addAll" -> "ADD_ELEMENT";
            case "put", "putAll" -> "PUT_ELEMENT";
            case "set", "offer", "push" -> "WRITE_ELEMENT";
            default -> "MUTATES_PARAMETER";
        };
    }

    // 作用：生成变更去重键；逻辑：组合参数下标、路径、操作与起始位置。
    private String mutationKey(ParameterMutationRecord mutation) {
        return mutation.parameterIndex() + "\u0001"
                + mutation.targetPath() + "\u0001"
                + mutation.operation() + "\u0001"
                + mutation.lineStart() + "\u0001"
                + mutation.columnStart();
    }

    // 作用：汇总字段写入；逻辑：扫描赋值与 setter 写入，按目标与条件聚合来源，并补充访问器自身写入。
    private List<FieldWriteSummary> fieldWrites(
            SourceFileRecord source,
            CallableScope scope,
            TypeDeclaration<?> type,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        GuardConditionResolver guards = new GuardConditionResolver(scope.callable(), context);
        Map<String, FieldWriteAccumulator> grouped = new LinkedHashMap<>();
        for (AssignExpr assignment : scope.callable().findAll(AssignExpr.class)) {
            if (!NodeFlowOwnership.belongsToOuterExecutable(assignment, scope.callable())
                    && !CallableOwnership.isCapturedMemberWrite(assignment, scope.callable())) {
                continue;
            }
            String path = fieldPath(assignment.getTarget(), context);
            if (path != null) {
                addFieldWrite(grouped, source, scope, nodes, accumulator, context,
                        path, assignment.getValue(), assignment, guards.conditionFor(assignment));
            }
        }
        for (MethodCallExpr call : scope.callable().findAll(MethodCallExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())
                    || !context.isSetter(call)
                    || call.getArguments().size() != 1) {
                continue;
            }
            String path = context.writeFieldPath(call);
            if (path != null) {
                addFieldWrite(grouped, source, scope, nodes, accumulator, context,
                        path, call.getArgument(0), call, guards.conditionFor(call));
            }
        }
        var accessor = accessorResolver.resolve(type).stream()
                .filter(candidate -> candidate.methodName().equals(scope.displayName()))
                .findFirst();
        if (accessor.isPresent() && "WRITE".equals(accessor.get().kind())) {
            ValueNodeRecord target = nodeCatalog.ensure(
                    source, scope, nodes, accumulator,
                    "this." + accessor.get().fieldName(), "FIELD", scope.callable()
            );
            grouped.putIfAbsent("this." + accessor.get().fieldName() + "\u0001",
                    new FieldWriteAccumulator("this." + accessor.get().fieldName(),
                            target.valueId(), new ArrayList<>(), "", scope.callable()));
        }
        return grouped.values().stream()
                .map(FieldWriteAccumulator::toSummary)
                .sorted(Comparator
                        .comparingInt(FieldWriteSummary::lineStart)
                        .thenComparing(FieldWriteSummary::target))
                .toList();
    }

    // 作用：累加单个字段写入；逻辑：确保目标节点存在，收集来源值并按路径与条件合并。
    private void addFieldWrite(
            Map<String, FieldWriteAccumulator> grouped,
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            String path,
            Expression valueExpression,
            Node anchor,
            String condition
    ) {
        ValueNodeRecord target = nodeCatalog.ensure(
                source, scope, nodes, accumulator, path, "FIELD", anchor
        );
        List<String> sourceIds = new ArrayList<>();
        for (ExpressionFlowSources.Source input : ExpressionFlowSources.collect(valueExpression, context)) {
            ValueNodeRecord origin = nodeCatalog.ensure(
                    source, scope, nodes, accumulator,
                    input.key(),
                    "EXPRESSION",
                    valueExpression
            );
            if (!origin.valueId().equals(target.valueId()) && !sourceIds.contains(origin.valueId())) {
                sourceIds.add(origin.valueId());
            }
        }
        String key = path + "\u0001" + condition;
        FieldWriteAccumulator write = grouped.computeIfAbsent(key,
                ignored -> new FieldWriteAccumulator(path, target.valueId(), new ArrayList<>(),
                        condition, anchor));
        for (String sourceId : sourceIds) {
            if (!write.sourceValueIds().contains(sourceId)) {
                write.sourceValueIds().add(sourceId);
            }
        }
    }

    // 作用：解析写入字段路径；逻辑：仅字段访问与下标访问返回逻辑键，其余返回 null。
    private String fieldPath(Expression target, JavaValueFlowContext context) {
        if (!(target instanceof FieldAccessExpr)
                && !(target instanceof com.github.javaparser.ast.expr.ArrayAccessExpr)) {
            return null;
        }
        String path = context.key(target);
        return path == null || path.isBlank() ? null : path;
    }

    // 作用：序列化摘要字段；逻辑：调用对象映射器，失败时抛出状态异常。
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize method summary", e);
        }
    }

    private record ReturnLeaf(Expression expression, String condition) {
    }

    private record FieldWriteAccumulator(
            String target,
            String targetValueId,
            List<String> sourceValueIds,
            String condition,
            Node evidence
    ) {
        // 作用：转换为摘要记录；逻辑：复制来源列表并补齐起止行号。
        private FieldWriteSummary toSummary() {
            return new FieldWriteSummary(
                    target,
                    targetValueId,
                    List.copyOf(sourceValueIds),
                    condition == null ? "" : condition,
                    evidence.getBegin().map(position -> position.line).orElse(1),
                    evidence.getBegin().map(position -> position.column).orElse(1),
                    evidence.getEnd().map(position -> position.line)
                            .orElse(evidence.getBegin().map(position -> position.line).orElse(1)),
                    evidence.getEnd().map(position -> position.column)
                            .orElse(evidence.getBegin().map(position -> position.column).orElse(1))
            );
        }
    }

    private record FieldWriteSummary(
            String target,
            String targetValueId,
            List<String> sourceValueIds,
            String condition,
            int lineStart,
            int columnStart,
            int lineEnd,
            int columnEnd
    ) {
    }

    private record ParameterMatch(
            int parameterIndex,
            String parameterName,
            String relativePath
    ) {
    }
}
