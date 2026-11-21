package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.PendingRefRecord;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.flow.NodeFlowOwnership;

import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.List;

// 作用：提取 getter/setter 产生的字段值流。
// 使用场景：getX() 与 setX(v) 在调用链中传播字段值。

/**
 * Emits field-write and condition-use edges for accessor calls. Assignment and
 * return reads are emitted by {@link AssignmentFlowExtractor}; keeping accessor
 * mutation here prevents the general assignment extractor from growing into a
 * second all-purpose analyzer.
 */
public final class AccessorFlowExtractor {
    private final ValueNodeCatalog nodeCatalog = new ValueNodeCatalog();

    // 作用：提取 setter 写入流；逻辑：遍历目标作用域内的 setter 调用，把参数来源连到字段写入节点。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        int statementOrdinal = 0;
        for (MethodCallExpr call : scope.callable().findAll(MethodCallExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())
                    || NodeFlowOwnership.belongsToOuterExecutable(call, scope.callable())
                    || !context.isSetter(call)) {
                continue;
            }
            String fieldPath = context.writeFieldPath(call);
            ValueNodeRecord target = nodes.get("field-write:" + fieldPath);
            if (target == null) {
                continue;
            }
            for (ExpressionFlowSources.Source input : ExpressionFlowSources.collect(call.getArgument(0), context)) {
                if ("BUILDER_RESULT".equals(input.edgeType())) {
                    // BuilderFlowExtractor maps builder properties directly to
                    // the receiving object's fields. Keeping the build() result
                    // as an object write only creates an internal target node.
                    continue;
                }
                ValueNodeRecord origin = nodes.get(input.key());
                if (origin == null || origin.valueId().equals(target.valueId())) {
                    continue;
                }
                addEdge(source, scope, origin, target, "FIELD_WRITE", call, accumulator, statementOrdinal++);
            }
        }
        statementOrdinal = linkNestedAccessorReads(source, scope, nodes, accumulator, context, statementOrdinal);
        registerUnverifiedAccessors(source, scope, nodes, accumulator, context);
    }

    /**
     * An accessor on a type that is neither in the analysed sources nor in the
     * supplied classpath cannot be proven to be a field. The path is still
     * published (the source really does call {@code getName()}), but it is
     * attached to an external boundary so the report says "provide classpath"
     * instead of an unexplained empty field.
     */
    // 作用：登记未经证明的外部访问器；逻辑：为无法核实的 getter 写入带外部原因、指向字段节点的待解析引用。
    private void registerUnverifiedAccessors(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        Set<String> emitted = new HashSet<>();
        for (MethodCallExpr call : scope.callable().findAll(MethodCallExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())
                    || !context.isUnverifiedAccessor(call)) {
                continue;
            }
            String key = context.key(call);
            if (key.isBlank()) {
                continue;
            }
            int line = call.getBegin().map(position -> position.line).orElse(1);
            int column = call.getBegin().map(position -> position.column).orElse(1);
            if (!emitted.add(key + "\u0001" + line + "\u0001" + column)) {
                continue;
            }
            ValueNodeRecord node = nodeCatalog.ensure(
                    source, scope, nodes, accumulator, key, "FIELD", call
            );
            accumulator.addPendingRef(new PendingRefRecord(
                    Ids.sha256(scope.symbolId(), "accessor", key, call.toString(),
                            String.valueOf(line), String.valueOf(column)),
                    Ids.sha256(scope.symbolId(), "accessor", String.valueOf(line)),
                    scope.symbolId(),
                    "FIELD_ACCESSOR",
                    key,
                    List.of(),
                    context.unverifiedAccessorReason(call),
                    source.fileId(),
                    line,
                    column,
                    call.getEnd().map(position -> position.line).orElse(line),
                    call.getEnd().map(position -> position.column).orElse(column),
                    "VIRTUAL",
                    null,
                    List.of(),
                    node.valueId()
            ));
        }
    }

    /**
     * A chained accessor such as {@code order.getBuyer().getName()} produces a
     * single leaf field path {@code order.buyer.name}. Without a link from the
     * intermediate object node ({@code order.buyer}) the whole-object trace of
     * that field reports no downstream use even though its sub-field is read.
     * Emitting the intermediate hop keeps every prefix of a field path
     * connected, so a query on {@code order.buyer} can reach {@code name}.
     */
    // 作用：连接嵌套访问器读取；逻辑：接收者本身是字段路径时，补一条接收者到其字段的读取边。
    private int linkNestedAccessorReads(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            int statementOrdinal
    ) {
        Set<String> emitted = new HashSet<>();
        for (MethodCallExpr call : scope.callable().findAll(MethodCallExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())
                    || !context.isGetter(call)) {
                continue;
            }
            Expression receiver = call.getScope().orElse(null);
            if (!isNestedReceiver(receiver, context)) {
                continue;
            }
            String receiverKey = context.key(receiver);
            String fieldKey = context.key(call);
            if (receiverKey.isBlank() || fieldKey.isBlank()
                    || receiverKey.startsWith("reference:")
                    || receiverKey.startsWith("call:")
                    || !fieldKey.startsWith(receiverKey + ".")) {
                continue;
            }
            int line = call.getBegin().map(position -> position.line).orElse(1);
            if (!emitted.add(receiverKey + "\u0001" + fieldKey + "\u0001" + line)) {
                continue;
            }
            ValueNodeRecord origin = nodeCatalog.ensure(
                    source, scope, nodes, accumulator, receiverKey, "FIELD", receiver
            );
            ValueNodeRecord target = nodeCatalog.ensure(
                    source, scope, nodes, accumulator, fieldKey, "FIELD", call
            );
            addEdge(source, scope, origin, target, "FIELD_READ",
                    "nested field access", call, accumulator, statementOrdinal++);
        }
        return statementOrdinal;
    }

    // 作用：判断接收者是否已是字段路径；逻辑：接受嵌套 getter 或业务字段访问，其余视为根对象。
    private boolean isNestedReceiver(Expression receiver, JavaValueFlowContext context) {
        if (receiver instanceof MethodCallExpr call) {
            return context.isGetter(call);
        }
        return receiver instanceof FieldAccessExpr field && context.isBusinessFieldAccess(field);
    }

    // 作用：写入一条字段写边；逻辑：用来源、目标与位置生成证据 ID，并追加精确静态数据边。
    private void addEdge(
            SourceFileRecord source,
            CallableScope scope,
            ValueNodeRecord origin,
            ValueNodeRecord target,
            String edgeType,
            Node evidence,
            JavaAnalysisAccumulator accumulator,
            int statementOrdinal
    ) {
        addEdge(source, scope, origin, target, edgeType, null, evidence, accumulator, statementOrdinal);
    }

    // 作用：写入一条带操作说明的字段边；逻辑：同样的证据 ID 规则，仅额外记录 operation 供输出解释。
    private void addEdge(
            SourceFileRecord source,
            CallableScope scope,
            ValueNodeRecord origin,
            ValueNodeRecord target,
            String edgeType,
            String operation,
            Node evidence,
            JavaAnalysisAccumulator accumulator,
            int statementOrdinal
    ) {
        int line = evidence.getBegin().map(position -> position.line).orElse(1);
        int column = evidence.getBegin().map(position -> position.column).orElse(1);
        int endLine = evidence.getEnd().map(position -> position.line).orElse(line);
        int endColumn = evidence.getEnd().map(position -> position.column).orElse(column);
        String evidenceId = Ids.sha256(
                scope.symbolId(),
                origin.valueId(),
                target.valueId(),
                edgeType,
                String.valueOf(line),
                String.valueOf(column)
        );
        accumulator.addDataEdge(new DataEdgeRecord(
                Ids.sha256(evidenceId, String.valueOf(statementOrdinal)),
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
                statementOrdinal
        ));
    }
}
