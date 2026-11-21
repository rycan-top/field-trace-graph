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
    private static final Set<String> WRAPPER_TYPES = Set.of(
            "Optional", "OptionalInt", "OptionalLong", "OptionalDouble",
            "CompletableFuture", "CompletionStage", "Future"
    );

    private final ValueNodeCatalog nodeCatalog = new ValueNodeCatalog();

    // 作用：提取 lambda 参数流；逻辑：逐个 lambda 解析来源容器，建立集合元素到参数的容器流边。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        int ordinal = 0;
        for (LambdaExpr lambda : scope.callable().findAll(LambdaExpr.class)) {
            if (lambda.getParameters().size() != 1
                    || lambda.getParameter(0).getNameAsString().isBlank()
                    || !CallableOwnership.belongsToCallableOrAnonymousBody(lambda, scope.callable())) {
                continue;
            }
            MethodCallExpr call = lambda.getParentNode()
                    .filter(parent -> parent instanceof MethodCallExpr owner)
                    .map(parent -> (MethodCallExpr) parent)
                    .orElse(null);
            String root = call == null
                    ? null
                    : call.getScope().map(this::rootName).orElse(null);
            if (isWrapperRoot(root, call, context)) {
                continue;
            }
            String elementKey = context.lambdaElementPath(lambda);
            if (elementKey == null) {
                if (root == null || root.isBlank()) {
                    continue;
                }
                elementKey = root + "[*]";
            } else if (!elementKey.endsWith("[*]")
                    && !elementKey.matches(".*\\[[^]]+]$")) {
                /*
                 * lambdaElementPath returns the container. The node is the
                 * element projection, which alias resolution recognises by
                 * its {@code [*]} suffix.
                 */
                elementKey = elementKey + "[*]";
            }
            ValueNodeRecord sourceNode = nodeCatalog.ensure(
                    source, scope, nodes, accumulator,
                    elementKey, "CONTAINER_ELEMENT", call == null ? lambda : call
            );
            ValueNodeRecord targetNode = nodeCatalog.ensure(
                    source, scope, nodes, accumulator,
                    context.lambdaParameterKey(lambda, 0), "LAMBDA_PARAMETER", lambda
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

    // 作用：判断 lambda 是否属于函数式包装器回调；逻辑：接收者声明类型或工厂/回调链根名命中包装器集合。
    private boolean isWrapperRoot(String root, MethodCallExpr call, JavaValueFlowContext context) {
        if (root == null || root.isBlank()) {
            return false;
        }
        String declared = context.declaredType(root);
        if (declared != null && WRAPPER_TYPES.contains(simpleType(declared))) {
            return true;
        }
        if (call == null || call.getScope().isEmpty()) {
            return false;
        }
        String owner = call.getScope()
                .map(Object::toString)
                .map(this::simpleName)
                .orElse("");
        return WRAPPER_TYPES.contains(owner);
    }

    // 作用：取简单类型名；逻辑：去掉泛型与包名前缀。
    private String simpleType(String type) {
        String value = type.trim();
        int generic = value.indexOf('<');
        if (generic >= 0) {
            value = value.substring(0, generic);
        }
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    // 作用：取简单名称；逻辑：保留最后一个点号后的文本。
    private String simpleName(String value) {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
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
