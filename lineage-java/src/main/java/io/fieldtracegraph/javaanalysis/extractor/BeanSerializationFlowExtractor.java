package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.Provenance;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.resolution.ExpressionTypeResolver;
import io.fieldtracegraph.javaanalysis.resolution.SourceTypeCatalog;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

// 作用：提取序列化框架实际输出的字段。
// 使用场景：Jackson、Fastjson、Gson 输出候选边并记录框架适配证据。

/**
 * Framework adapter for Bean-to-JSON serialization. Fastjson, Jackson and Gson
 * hide field reads inside the serializer, so the static graph cannot see
 * {@code order.status -> json}. The adapter expands declared fields into
 * {@code SERIALIZE_FIELD} edges.
 *
 * <p>These edges are deliberately {@code CANDIDATE}: serializer configuration
 * can rename, filter or customize fields at runtime. An imported runtime trace
 * can promote the same logical edge to {@code RUNTIME_OBSERVED} without
 * discarding this static evidence.</p>
 */
public final class BeanSerializationFlowExtractor {
    private static final Set<String> SERIALIZE_METHODS = Set.of(
            "toJSONString", "toJSON", "toJSONBytes",
            "writeValueAsString", "writeValueAsBytes",
            "toJson", "convertValue"
    );

    private final ValueNodeCatalog nodeCatalog = new ValueNodeCatalog();

    // 作用：提取 Bean 序列化字段流；逻辑：识别序列化调用，按类型目录展开字段并生成候选证据边。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            SourceTypeCatalog catalog
    ) {
        if (catalog == null) {
            return;
        }
        ExpressionTypeResolver typeResolver = new ExpressionTypeResolver(catalog);
        Set<String> emitted = new LinkedHashSet<>();
        int ordinal = 0;
        for (MethodCallExpr call : scope.callable().findAll(MethodCallExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())
                    || !SERIALIZE_METHODS.contains(call.getNameAsString())) {
                continue;
            }
            Expression value = serializedValue(call);
            if (value == null) {
                continue;
            }
            String beanType = typeResolver.resolve(value, scope, context);
            if (beanType == null || catalog.type(beanType) == null) {
                continue;
            }
            String beanKey = context.key(value);
            String resultKey = context.key(call);
            if (beanKey == null || beanKey.isBlank() || resultKey == null || resultKey.isBlank()) {
                continue;
            }
            ValueNodeRecord result = nodeCatalog.ensure(
                    source, scope, nodes, accumulator, resultKey, "CALL_RESULT", call);
            for (String field : catalog.fieldNames(beanType)) {
                if (catalog.isSerializationIgnored(beanType, field)) {
                    continue;
                }
                String fieldKey = beanKey + "." + field;
                if (!emitted.add(fieldKey + "\u0001" + resultKey)) {
                    continue;
                }
                ValueNodeRecord origin = nodeCatalog.ensure(
                        source, scope, nodes, accumulator, fieldKey, "FIELD", value);
                ordinal = emit(source, scope, accumulator, origin, result, field, call, ordinal);
            }
        }
    }

    // 作用：定位序列化输入对象；逻辑：兼容 convertValue、toJson 与单参或无参序列化方法。
    private Expression serializedValue(MethodCallExpr call) {
        String name = call.getNameAsString();
        if ("convertValue".equals(name) && call.getArguments().size() == 2) {
            return call.getArgument(0);
        }
        if ("toJson".equals(name)
                && !call.getArguments().isEmpty()
                && !"JSONObject".equals(simpleReceiver(call))) {
            return call.getArgument(0);
        }
        if (!call.getArguments().isEmpty()) {
            return call.getArgument(0);
        }
        return call.getScope().orElse(null);
    }

    // 作用：取简单接收者名；逻辑：去掉包名前缀，仅保留最后一段。
    private String simpleReceiver(MethodCallExpr call) {
        String receiver = call.getScope().map(Object::toString).orElse("");
        int dot = receiver.lastIndexOf('.');
        return dot < 0 ? receiver : receiver.substring(dot + 1);
    }

    // 作用：写序列化字段边；逻辑：生成稳定证据 ID，以候选级别与框架适配来源追加数据边。
    private int emit(
            SourceFileRecord source,
            CallableScope scope,
            JavaAnalysisAccumulator accumulator,
            ValueNodeRecord origin,
            ValueNodeRecord target,
            String field,
            Node anchor,
            int ordinal
    ) {
        int line = anchor.getBegin().map(position -> position.line).orElse(1);
        int column = anchor.getBegin().map(position -> position.column).orElse(1);
        String evidenceId = Ids.sha256(
                scope.symbolId(), origin.valueId(), target.valueId(),
                "SERIALIZE_FIELD", String.valueOf(line), String.valueOf(column));
        accumulator.addDataEdge(new DataEdgeRecord(
                Ids.sha256(evidenceId, String.valueOf(ordinal)),
                scope.symbolId(),
                origin.valueId(),
                target.valueId(),
                "DERIVED_FROM",
                "framework serializer field " + field,
                "CANDIDATE",
                evidenceId,
                null,
                null,
                source.fileId(),
                line,
                column,
                anchor.getEnd().map(position -> position.line).orElse(line),
                anchor.getEnd().map(position -> position.column).orElse(column),
                ordinal,
                Provenance.FRAMEWORK_ADAPTER.name()
        ));
        return ordinal + 1;
    }
}
