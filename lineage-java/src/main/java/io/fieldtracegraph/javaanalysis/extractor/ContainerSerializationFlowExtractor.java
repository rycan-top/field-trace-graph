package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.resolution.NonLineageCallFilter;

import java.util.Map;
import java.util.Set;

// 作用：提取容器被序列化时暴露的元素字段。
// 使用场景：接口返回 List<VO> 时确定字段投影。

/**
 * Connects individual collection/map entries to a serialized result. A whole
 * container node already flows to calls such as {@code JSON.toJSONString(map)};
 * this extractor preserves entry-level lineage through that boundary.
 */
public final class ContainerSerializationFlowExtractor {
    private static final Set<String> JSON_SERIALIZERS = Set.of("toJSONString", "toJSON");

    private final ValueEdgeEmitter edgeEmitter = new ValueEdgeEmitter();
    private final NonLineageCallFilter nonLineageCallFilter = new NonLineageCallFilter();

    // 作用：提取容器序列化流；逻辑：识别序列化调用，把容器内各条目连接到序列化结果节点。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        int ordinal = 0;
        for (MethodCallExpr call : scope.callable().findAll(MethodCallExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())) {
                continue;
            }
            if (nonLineageCallFilter.isInDiagnosticContext(call)) {
                continue;
            }
            String containerKey = serializedContainerKey(call, context);
            if (containerKey == null) {
                continue;
            }
            ValueNodeRecord result = edgeEmitter.ensure(
                    source, scope, nodes, accumulator, context.key(call), call
            );
            for (Map.Entry<String, ValueNodeRecord> entry : nodes.entrySet()) {
                ValueNodeRecord value = entry.getValue();
                if (!"CONTAINER_ELEMENT".equals(value.valueKind())
                        || !entry.getKey().startsWith(containerKey + "[")
                        || entry.getKey().endsWith("[*]")) {
                    continue;
                }
                edgeEmitter.addEdge(
                        source,
                        scope,
                        accumulator,
                        value,
                        result,
                        "DERIVED_FROM",
                        "serialize container entry",
                        call,
                        ordinal++
                );
            }
        }
    }

    // 作用：解析被序列化容器键；逻辑：仅当表达式根确为容器时返回其逻辑键。
    private String serializedContainerKey(MethodCallExpr call, JavaValueFlowContext context) {
        Expression candidate = serializedContainerExpression(call);
        if (candidate == null) {
            return null;
        }
        String rootName = rootName(candidate);
        return rootName != null && context.isContainerRoot(rootName)
                ? context.key(candidate)
                : null;
    }

    // 作用：定位序列化入参；逻辑：兼容 Fastjson、String.valueOf 与无参 toJSONString 三类写法。
    private Expression serializedContainerExpression(MethodCallExpr call) {
        String name = call.getNameAsString();
        String receiver = simpleReceiver(call);
        if (JSON_SERIALIZERS.contains(name) && call.getArguments().size() == 1) {
            return call.getArgument(0);
        }
        if ("valueOf".equals(name)
                && "String".equals(receiver)
                && call.getArguments().size() == 1) {
            return call.getArgument(0);
        }
        if ("toJSONString".equals(name) && call.getArguments().isEmpty()) {
            return call.getScope().orElse(null);
        }
        return null;
    }

    // 作用：取简单接收者名；逻辑：去掉包名前缀，仅保留最后一段。
    private String simpleReceiver(MethodCallExpr call) {
        String receiver = call.getScope().map(Object::toString).orElse("");
        int dot = receiver.lastIndexOf('.');
        return dot < 0 ? receiver : receiver.substring(dot + 1);
    }

    // 作用：提取表达式根名；逻辑：剥离括号、强转、调用作用域、字段访问与下标后返回变量名。
    private String rootName(Expression expression) {
        Expression current = expression;
        while (true) {
            if (current instanceof com.github.javaparser.ast.expr.EnclosedExpr enclosed) {
                current = enclosed.getInner();
            } else if (current instanceof com.github.javaparser.ast.expr.CastExpr cast) {
                current = cast.getExpression();
            } else {
                break;
            }
        }
        while (current instanceof MethodCallExpr call && call.getScope().isPresent()) {
            current = call.getScope().orElseThrow();
        }
        while (current instanceof com.github.javaparser.ast.expr.FieldAccessExpr field) {
            current = field.getScope();
        }
        while (current instanceof com.github.javaparser.ast.expr.ArrayAccessExpr access) {
            current = access.getName();
        }
        return current instanceof com.github.javaparser.ast.expr.NameExpr name
                ? name.getNameAsString()
                : null;
    }
}
