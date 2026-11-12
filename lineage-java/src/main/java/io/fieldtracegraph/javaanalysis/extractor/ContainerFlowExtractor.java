package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.JavaExpressionIdentity;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.GuardConditionResolver;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;

import java.util.List;
import java.util.Map;

// 作用：提取集合与映射的读写值流。
// 使用场景：add、put、get 等操作在容器中的传播。

/**
 * Emits element-to-container edges for collection/map mutation and bulk copy
 * operations. The logical destination is always an element key such as
 * {@code list[*]} or {@code map[#key]}, so a later read keeps the same value
 * identity.
 */
public final class ContainerFlowExtractor {
    private final ValueNodeCatalog nodeCatalog = new ValueNodeCatalog();

    // 作用：提取容器写入流；逻辑：识别单元素写入与批量复制，生成参数变更、元素写入与元素复制边。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        int statementOrdinal = 0;
        GuardConditionResolver guards = new GuardConditionResolver(scope.callable(), context);
        for (MethodCallExpr call : scope.callable().findAll(MethodCallExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())
                    || !context.isContainerMutation(call)) {
                continue;
            }
            String targetKey = context.mutationTarget(call);
            if (targetKey == null) {
                targetKey = context.bulkCopyTarget(call);
            }
            if (targetKey == null) {
                continue;
            }
            ValueNodeRecord target = nodeCatalog.ensure(
                    source,
                    scope,
                    nodes,
                    accumulator,
                    targetKey,
                    "CONTAINER_ELEMENT",
                    call
            );
            if (call.getScope().isPresent()) {
                String root = CallableOwnership.rootName(call.getScope().orElseThrow());
                if (isParameterRoot(scope, root)) {
                    ValueNodeRecord container = nodeCatalog.ensure(
                            source,
                            scope,
                            nodes,
                            accumulator,
                            context.key(call.getScope().orElseThrow()),
                            "EXPRESSION",
                            call.getScope().orElseThrow()
                    );
                    addEdge(source, scope, container, target, "MUTATES_PARAMETER",
                            call, guards, context, accumulator, statementOrdinal++);
                }
            }
            List<Expression> arguments = call.getArguments();
            if (arguments.isEmpty()) {
                continue;
            }
            String bulkTarget = context.bulkCopyTarget(call);
            if (bulkTarget != null) {
                Expression copiedCollection = arguments.get(0);
                String sourceKey = context.key(copiedCollection);
                if (sourceKey == null || sourceKey.isBlank()) {
                    continue;
                }
                sourceKey = context.elementKeyForContainer(sourceKey);
                ValueNodeRecord copiedElements = nodeCatalog.ensure(
                        source,
                        scope,
                        nodes,
                        accumulator,
                        sourceKey,
                        "CONTAINER_ELEMENT",
                        copiedCollection
                );
                addEdge(source, scope, copiedElements, target, "COPIES_ELEMENTS",
                        call, guards, context, accumulator, statementOrdinal++);
                continue;
            }
            Expression value = arguments.get(arguments.size() - 1);
            ValueNodeRecord expressionValue = nodeCatalog.ensure(
                    source,
                    scope,
                    nodes,
                    accumulator,
                    context.key(value),
                    "EXPRESSION",
                    value
            );
            addEdge(source, scope, expressionValue, target, "WRITES_ELEMENT",
                    call, guards, context, accumulator, statementOrdinal++);
            for (ExpressionFlowSources.Source input : ExpressionFlowSources.collect(value, context)) {
                if (input.key() == null || input.key().isBlank()) {
                    continue;
                }
                ValueNodeRecord element = nodeCatalog.ensure(
                        source,
                        scope,
                        nodes,
                        accumulator,
                        input.key(),
                        "EXPRESSION",
                        value
                );
                addEdge(source, scope, element, target, "WRITES_ELEMENT",
                        call, guards, context, accumulator, statementOrdinal++);
            }
        }
    }

    // 作用：判断根是否为参数；逻辑：根名与当前可调用体的任一参数名相同。
    private boolean isParameterRoot(CallableScope scope, String root) {
        return root != null && scope.callable().getParameters().stream()
                .anyMatch(parameter -> root.equals(parameter.getNameAsString()));
    }

    // 作用：写入容器数据边；逻辑：跳过自环，合成操作与守卫条件后追加精确静态边。
    private void addEdge(
            SourceFileRecord source,
            CallableScope scope,
            ValueNodeRecord element,
            ValueNodeRecord container,
            String edgeType,
            MethodCallExpr call,
            GuardConditionResolver guards,
            JavaValueFlowContext context,
            JavaAnalysisAccumulator accumulator,
            int statementOrdinal
    ) {
        if (element.valueId().equals(container.valueId())) {
            return;
        }
        int line = call.getBegin().map(position -> position.line).orElse(1);
        int column = call.getBegin().map(position -> position.column).orElse(1);
        int endLine = call.getEnd().map(position -> position.line).orElse(1);
        int endColumn = call.getEnd().map(position -> position.column).orElse(1);
        String evidenceId = Ids.sha256(scope.symbolId(), element.valueId(), container.valueId(), edgeType, String.valueOf(line));
        String branchCondition = guards.conditionFor(call);
        String callText = JavaExpressionIdentity.normalizedMapOperation(call);
        String operation = branchCondition.isBlank()
                ? callText
                : callText + " && " + branchCondition;
        accumulator.addDataEdge(new DataEdgeRecord(
                Ids.sha256(scope.symbolId(), element.valueId(), container.valueId(), edgeType, String.valueOf(line), String.valueOf(column), String.valueOf(statementOrdinal)),
                scope.symbolId(),
                element.valueId(),
                container.valueId(),
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
                statementOrdinal
        ));
    }
}
