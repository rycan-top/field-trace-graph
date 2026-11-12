package io.fieldtracegraph.core.query;

import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：解析 Lambda 参数与集合元素之间的别名关系。
// 使用场景：集合遍历或 Stream 中把参数追溯到元素字段。

/**
 * Presents collection-element fields through the lambda parameter that owns
 * them. The graph deliberately canonicalises {@code user.expectPrice} to
 * {@code top10Users[*].expectPrice}; this resolver adds the user-facing alias
 * without duplicating data edges or weakening field-existence checks.
 */
final class LambdaElementAliasResolver {
    // 作用：收集 Lambda 参数与集合元素之间的别名；逻辑：扫描数据边证据，建立元素根与参数后缀的对应关系。
    List<AliasedField> aliases(
            String methodId,
            List<ValueNodeRecord> nodes,
            List<DataEdgeRecord> edges
    ) {
        Map<String, String> elementRootByLambda = elementRoots(methodId, nodes, edges);
        if (elementRootByLambda.isEmpty()) {
            return List.of();
        }
        Set<String> evidenceNodeIds = evidenceNodeIds(edges);
        List<AliasedField> result = new ArrayList<>();
        for (ValueNodeRecord node : nodes) {
            if (!methodId.equals(node.scopeSymbolId())
                    || !evidenceNodeIds.contains(node.valueId())
                    || node.canonicalValue() == null) {
                continue;
            }
            for (Map.Entry<String, String> entry : elementRootByLambda.entrySet()) {
                String suffix = suffix(node.canonicalValue(), entry.getValue());
                if (suffix == null || suffix.isBlank()) {
                    continue;
                }
                result.add(new AliasedField(
                        entry.getKey() + suffix,
                        node
                ));
            }
        }
        return List.copyOf(result);
    }

    // 作用：把值引用解析为元素别名；逻辑：查别名表，命中后替换为元素根并保留剩余字段路径。
    ValueRef resolve(
            String methodId,
            String targetExpression,
            List<ValueNodeRecord> nodes,
            List<DataEdgeRecord> edges
    ) {
        for (AliasedField alias : aliases(methodId, nodes, edges)) {
            if (alias.expression().equals(targetExpression)) {
                return new ValueRef(alias.node().valueId(), List.of());
            }
        }
        return null;
    }

    // 作用：构建元素根映射；逻辑：按作用域与字段路径聚合容器元素的规范值。
    private Map<String, String> elementRoots(
            String methodId,
            List<ValueNodeRecord> nodes,
            List<DataEdgeRecord> edges
    ) {
        Map<String, ValueNodeRecord> nodesById = new LinkedHashMap<>();
        for (ValueNodeRecord node : nodes) {
            nodesById.put(node.valueId(), node);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (DataEdgeRecord edge : edges) {
            if (!methodId.equals(edge.scopeSymbolId()) || !"CONTAINER_FLOW".equals(edge.edgeType())) {
                continue;
            }
            ValueNodeRecord source = nodesById.get(edge.srcValueId());
            ValueNodeRecord target = nodesById.get(edge.dstValueId());
            if (source == null || target == null
                    || !"LAMBDA_PARAMETER".equals(target.valueKind())
                    || !"CONTAINER_ELEMENT".equals(source.valueKind())
                    || source.canonicalValue() == null
                    || !source.canonicalValue().endsWith("[*]")) {
                continue;
            }
            result.putIfAbsent(target.canonicalValue(), source.canonicalValue());
        }
        return result;
    }

    // 作用：去掉元素根前缀得到字段后缀；逻辑：前缀匹配时截断，否则返回原值。
    private String suffix(String canonicalValue, String elementRoot) {
        if (canonicalValue.startsWith(elementRoot + ".")) {
            return canonicalValue.substring(elementRoot.length());
        }
        if (canonicalValue.startsWith(elementRoot + "[")) {
            return canonicalValue.substring(elementRoot.length());
        }
        return null;
    }

    // 作用：收集边上的证据节点 ID；逻辑：遍历数据边并汇总证据对应的节点标识。
    private Set<String> evidenceNodeIds(List<DataEdgeRecord> edges) {
        Set<String> result = new HashSet<>();
        for (DataEdgeRecord edge : edges) {
            result.add(edge.srcValueId());
            result.add(edge.dstValueId());
        }
        return result;
    }

    record AliasedField(String expression, ValueNodeRecord node) {
    }
}
