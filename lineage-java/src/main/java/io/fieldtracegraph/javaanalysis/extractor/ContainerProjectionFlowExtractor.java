package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.ForEachStmt;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

// 作用：提取容器元素投影流。
// 使用场景：for-each 迭代与 list[*].field 元素字段读取。

/**
 * Connects a container value to the element values read from it. Element reads
 * and writes were already modelled, but the container itself was never
 * connected to its elements, so {@code bean.getItems()} looked unused even
 * when every element field was consumed. This pass adds only projection edges
 * backed by an existing element node or by a for-each loop.
 */
public final class ContainerProjectionFlowExtractor {
    private static final Set<String> LOGICAL_PREFIXES = Set.of(
            "call:", "new:", "reference:", "phi:", "branch:", "before:",
            "field-write:", "method-ref:"
    );

    private final ValueNodeCatalog nodeCatalog = new ValueNodeCatalog();
    private final ValueEdgeEmitter edgeEmitter = new ValueEdgeEmitter();

    // 作用：提取容器投影边；逻辑：先连 for-each 迭代元素，再为已有元素字段节点补父链。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        int ordinal = 0;
        for (ForEachStmt loop : scope.callable().findAll(ForEachStmt.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(loop, scope.callable())) {
                continue;
            }
            Expression iterable = loop.getIterable();
            String containerKey = context.key(iterable);
            if (!isValueKey(containerKey)) {
                continue;
            }
            String elementKey = context.elementKeyForContainer(containerKey);
            if (elementKey.equals(containerKey)) {
                continue;
            }
            ValueNodeRecord container = nodeCatalog.ensure(
                    source, scope, nodes, accumulator, containerKey, "EXPRESSION", iterable
            );
            ValueNodeRecord element = nodeCatalog.ensure(
                    source, scope, nodes, accumulator, elementKey, "CONTAINER_ELEMENT", loop.getVariable()
            );
            edgeEmitter.addEdge(
                    source, scope, accumulator, container, element,
                    "CONTAINER_FLOW", "iterates " + containerKey,
                    iterable, ordinal++
            );
        }
        projectElementFields(source, scope, nodes, accumulator, ordinal);
    }

    // 作用：补元素字段父链；逻辑：对每个含下标段的节点，连接已存在的直接父容器节点。
    private void projectElementFields(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            int statementOrdinal
    ) {
        Set<ValueNodeRecord> unique = new LinkedHashSet<>(nodes.values());
        int ordinal = statementOrdinal;
        for (ValueNodeRecord node : unique) {
            String key = node.canonicalValue();
            String parentKey = containerParent(key);
            if (parentKey == null) {
                continue;
            }
            ValueNodeRecord parent = nodes.get(parentKey);
            if (parent == null || parent.valueId().equals(node.valueId())) {
                continue;
            }
            addEdgeAt(
                    source, scope, accumulator, parent, node, "CONTAINER_FLOW",
                    "element of " + parentKey, node, ordinal++
            );
        }
    }

    // 作用：解析直接父容器键；逻辑：去掉末尾下标段，下标后仍有字段时保留元素前缀。
    private String containerParent(String key) {
        if (!isValueKey(key) || key.indexOf('[') < 0) {
            return null;
        }
        int close = key.lastIndexOf(']');
        if (close < 0) {
            return null;
        }
        int depth = 0;
        int open = -1;
        for (int i = close; i >= 0; i--) {
            char c = key.charAt(i);
            if (c == ']') {
                depth++;
            } else if (c == '[') {
                depth--;
                if (depth == 0) {
                    open = i;
                    break;
                }
            }
        }
        if (open <= 0) {
            return null;
        }
        String parent = close == key.length() - 1
                ? key.substring(0, open)
                : key.substring(0, close + 1);
        return parent.isBlank() || parent.equals(key) ? null : parent;
    }

    // 作用：判断值键是否可作为容器身份；逻辑：排除空白与逻辑前缀键。
    private boolean isValueKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        for (String prefix : LOGICAL_PREFIXES) {
            if (key.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    // 作用：按节点位置写入投影边；逻辑：复用节点行列作为证据区间，跳过自环。
    private void addEdgeAt(
            SourceFileRecord source,
            CallableScope scope,
            JavaAnalysisAccumulator accumulator,
            ValueNodeRecord origin,
            ValueNodeRecord target,
            String edgeType,
            String operation,
            ValueNodeRecord anchor,
            int statementOrdinal
    ) {
        if (origin.valueId().equals(target.valueId())) {
            return;
        }
        int line = anchor.lineStart();
        int column = anchor.columnStart();
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
                anchor.lineEnd(),
                anchor.columnEnd(),
                statementOrdinal
        ));
    }
}
