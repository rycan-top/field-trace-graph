package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;

import java.util.Map;
import java.util.Set;

// 作用：提取 Lambda 参数的类型推断与值流。
// 使用场景：隐式类型参数追溯到集合元素类型。

/**
 * Connects a collection element to a lambda parameter. The parameter keeps its
 * own value root so field paths such as {@code user.expectPrice} remain
 * selectable, while the collection-to-element edge preserves the upstream
 * source.
 */
public final class LambdaParameterFlowExtractor {
    private static final Set<String> ELEMENT_CALLBACK_METHODS = Set.of(
            "forEach", "forEachOrdered", "peek", "map", "flatMap", "filter",
            "anyMatch", "allMatch", "noneMatch", "takeWhile", "dropWhile",
            "mapToInt", "mapToLong", "mapToDouble", "replaceAll", "compute",
            "computeIfPresent", "computeIfAbsent", "merge"
    );
    private final ValueNodeCatalog nodeCatalog = new ValueNodeCatalog();

    // 作用：提取 lambda 参数流；逻辑：识别元素回调，建立集合元素到 lambda 参数的容器流边。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        int ordinal = 0;
        for (MethodCallExpr call : scope.callable().findAll(MethodCallExpr.class)) {
            if (!ELEMENT_CALLBACK_METHODS.contains(call.getNameAsString())
                    || call.getArguments().size() != 1
                    || !(call.getArgument(0) instanceof LambdaExpr lambda)
                    || lambda.getParameters().size() != 1
                    || !CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())) {
                continue;
            }
            String elementKey = context.streamElementKey(call);
            if (elementKey == null) {
                String root = call.getScope().map(this::rootName).orElse(null);
                if (root == null || root.isBlank()) {
                    continue;
                }
                elementKey = root + "[*]";
            }
            if (lambda.getParameter(0).getNameAsString().isBlank()) {
                continue;
            }
            ValueNodeRecord sourceNode = nodeCatalog.ensure(
                    source, scope, nodes, accumulator,
                    elementKey, "CONTAINER_ELEMENT", call
            );
            ValueNodeRecord targetNode = nodeCatalog.ensure(
                    source, scope, nodes, accumulator,
                    lambda.getParameter(0).getNameAsString(), "LAMBDA_PARAMETER", lambda
            );
            if (sourceNode.valueId().equals(targetNode.valueId())) {
                continue;
            }
            int line = lambda.getBegin().map(position -> position.line).orElse(1);
            int column = lambda.getBegin().map(position -> position.column).orElse(1);
            String evidenceId = Ids.sha256(
                    scope.symbolId(), sourceNode.valueId(), targetNode.valueId(),
                    "CONTAINER_FLOW", String.valueOf(line), String.valueOf(column)
            );
            accumulator.addDataEdge(new DataEdgeRecord(
                    Ids.sha256(evidenceId, String.valueOf(ordinal)),
                    scope.symbolId(),
                    sourceNode.valueId(),
                    targetNode.valueId(),
                    "CONTAINER_FLOW",
                    "lambda parameter from collection element",
                    "EXACT_STATIC",
                    evidenceId,
                    null,
                    null,
                    source.fileId(),
                    line,
                    column,
                    lambda.getEnd().map(position -> position.line).orElse(line),
                    lambda.getEnd().map(position -> position.column).orElse(column),
                    ordinal++
            ));
        }
    }

    // 作用：提取表达式根名；逻辑：剥离调用作用域、字段访问与下标后返回变量名。
    private String rootName(Expression expression) {
        Expression current = expression;
        while (current instanceof MethodCallExpr call && call.getScope().isPresent()) {
            current = call.getScope().orElseThrow();
        }
        while (current instanceof com.github.javaparser.ast.expr.FieldAccessExpr field) {
            current = field.getScope();
        }
        while (current instanceof com.github.javaparser.ast.expr.ArrayAccessExpr access) {
            current = access.getName();
        }
        return current instanceof NameExpr name ? name.getNameAsString() : null;
    }
}
