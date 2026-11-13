package io.fieldtracegraph.core.query;

import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.TargetCandidate;
import io.fieldtracegraph.core.model.ValueNodeRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：把目标表达式解析成候选值节点。
// 使用场景：trace --target result.status 时确定追踪起点。

/**
 * Builds the user-facing target list for one method. It combines method-scope
 * roots with evidence-backed field paths discovered in callee scopes, so a
 * parameter such as {@code orderRequest} exposes the fields actually read or
 * written downstream instead of only the object root.
 */
public final class TargetCandidateResolver {
    private static final Set<String> ROOT_KINDS = Set.of(
            "PARAMETER", "LAMBDA_PARAMETER", "LOCAL", "FIELD", "RETURN", "CALL_RESULT"
    );
    private static final Map<String, Integer> KIND_ORDER = Map.of(
            "PARAMETER", 0,
            "LAMBDA_PARAMETER", 1,
            "LOCAL", 2,
            "FIELD", 3,
            "RETURN", 4,
            "CALL_RESULT", 5
    );
    private final LambdaElementAliasResolver lambdaElementAliasResolver = new LambdaElementAliasResolver();

    // 作用：解析方法内可追踪的目标；逻辑：收集方法根、过滤内部节点并按类型排序生成候选。
    public List<TargetCandidate> resolve(
            String methodId,
            List<ValueNodeRecord> nodes,
            List<DataEdgeRecord> edges
    ) {
        Set<String> evidenceNodeIds = evidenceNodeIds(edges);
        Set<String> builderResultNodeIds = builderResultNodeIds(edges);
        Map<String, ValueNodeRecord> roots = methodRoots(
                methodId, nodes, evidenceNodeIds, builderResultNodeIds
        );
        Map<String, TargetCandidate> candidates = new LinkedHashMap<>();
        for (ValueNodeRecord root : roots.values()) {
            if (isAmbiguousAnonymousValue(root, nodes)) {
                continue;
            }
            candidates.putIfAbsent(root.canonicalValue(), candidate(root, root.canonicalValue()));
        }
        for (ValueNodeRecord field : nodes) {
            if (!"FIELD".equals(field.valueKind())
                    || !rootScopeMatches(field, roots)
                    || field.accessPath() == null
                    || field.accessPath().isEmpty()
                    || field.rootName() == null
                    || "this".equals(field.rootName())
                    || !evidenceNodeIds.contains(field.valueId())
                    || isInternalVersion(field)) {
                continue;
            }
            ValueNodeRecord root = roots.get(field.rootName());
            if (root == null) {
                String baseRoot = containerBaseRoot(field.rootName());
                root = roots.get(baseRoot);
            }
            if (root == null) {
                continue;
            }
            String expression = field.rootName() + "." + String.join(".", field.accessPath());
            candidates.putIfAbsent(expression, new TargetCandidate(
                    root.valueId(),
                    expression,
                    "FIELD",
                    root.scopeSymbolId(),
                    expression
            ));
        }
        for (ValueNodeRecord element : nodes) {
            if (!"CONTAINER_ELEMENT".equals(element.valueKind())
                    || !evidenceNodeIds.contains(element.valueId())
                    || element.canonicalValue().contains("[*]")) {
                continue;
            }
            String rootName = containerRoot(element.canonicalValue());
            if (rootName == null) {
                continue;
            }
            ValueNodeRecord root = roots.get(rootName);
            if (root == null || !root.scopeSymbolId().equals(element.scopeSymbolId())) {
                continue;
            }
            candidates.putIfAbsent(element.canonicalValue(), new TargetCandidate(
                    root.valueId(),
                    element.canonicalValue(),
                    "MAP_ENTRY",
                    element.scopeSymbolId(),
                    element.canonicalValue()
            ));
        }
        for (LambdaElementAliasResolver.AliasedField alias
                : lambdaElementAliasResolver.aliases(methodId, nodes, edges)) {
            candidates.putIfAbsent(alias.expression(), new TargetCandidate(
                    alias.node().valueId(),
                    alias.expression(),
                    "FIELD",
                    alias.node().scopeSymbolId(),
                    alias.expression()
            ));
        }
        List<TargetCandidate> result = new ArrayList<>(candidates.values());
        result.sort(Comparator
                .comparingInt((TargetCandidate candidate) -> KIND_ORDER.getOrDefault(candidate.kind(), 99))
                .thenComparing(TargetCandidate::targetExpression));
        return List.copyOf(result);
    }

    // 作用：构建方法内的根节点映射；逻辑：按名称归组参数、局部变量与字段。
    private Map<String, ValueNodeRecord> methodRoots(
            String methodId,
            List<ValueNodeRecord> nodes,
            Set<String> evidenceNodeIds,
            Set<String> builderResultNodeIds
    ) {
        Set<String> visibleObjectRoots = nodes.stream()
                .filter(node -> methodId.equals(node.scopeSymbolId()))
                .filter(node -> node.accessPath() == null || node.accessPath().isEmpty())
                .filter(node -> "PARAMETER".equals(node.valueKind())
                        || "LAMBDA_PARAMETER".equals(node.valueKind())
                        || "LOCAL".equals(node.valueKind())
                        || "this".equals(node.canonicalValue()))
                .map(ValueNodeRecord::canonicalValue)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, ValueNodeRecord> latest = new LinkedHashMap<>();
        nodes.stream()
                .filter(node -> methodId.equals(node.scopeSymbolId()))
                .filter(node -> ROOT_KINDS.contains(node.valueKind()))
                .filter(node -> !isInternalVersion(node))
                .filter(node -> !"CALL_RESULT".equals(node.valueKind())
                        || evidenceNodeIds.contains(node.valueId()))
                .filter(node -> !builderResultNodeIds.contains(node.valueId()))
                .filter(node -> !isHashedCallValue(node))
                .filter(node -> !isAnonymousConstructorField(node))
                .filter(node -> !"FIELD".equals(node.valueKind())
                        || isFieldRootVisible(node, visibleObjectRoots))
                .sorted(Comparator
                        .comparingInt(ValueNodeRecord::declarationOffset)
                        .thenComparingInt(ValueNodeRecord::definitionOrdinal))
                .forEach(node -> latest.put(node.canonicalValue(), node));
        return latest;
    }

    // 作用：判断是否为匿名构造器字段；逻辑：字段名带匿名内部类标记时返回 true。
    private boolean isAnonymousConstructorField(ValueNodeRecord node) {
        return "CALL_RESULT".equals(node.valueKind())
                && node.canonicalValue().startsWith("new:")
                && node.canonicalValue().contains("#")
                && node.accessPath() != null
                && !node.accessPath().isEmpty();
    }

    /**
     * Internal call identities carry a use-site hash. They are useful for
     * traversal but are not user-selectable field expressions; hiding them
     * keeps targets focused on parameters, locals, fields and constructor
     * results.
     */
    // 作用：判断是否为哈希调用值；逻辑：值名带调用哈希前缀时返回 true。
    private boolean isHashedCallValue(ValueNodeRecord node) {
        return "CALL_RESULT".equals(node.valueKind())
                && node.canonicalValue() != null
                && node.canonicalValue().startsWith("call:")
                && node.canonicalValue().contains("#");
    }

    /**
     * Static type chains such as {@code com.alibaba.fastjson.JSON} and enum
     * constants are represented by FIELD nodes too, but they are not object
     * roots a user can use as a line-of-business target. A field root is only
     * visible when it starts from a declared parameter, local variable or
     * {@code this}; nested object fields remain visible through their prefix.
     */
    // 作用：判断字段根是否可见；逻辑：字段所属对象根在可见集合中才算。
    private boolean isFieldRootVisible(ValueNodeRecord field, Set<String> visibleObjectRoots) {
        String rootName = field.rootName();
        if (rootName == null || rootName.isBlank()) {
            return false;
        }
        for (String objectRoot : visibleObjectRoots) {
            if (rootName.equals(objectRoot)
                    || rootName.startsWith(objectRoot + ".")
                    || rootName.startsWith(objectRoot + "[")) {
                return true;
            }
        }
        return false;
    }

    // 作用：提取容器基础根名；逻辑：去掉元素或下标后缀。
    private String containerBaseRoot(String rootName) {
        int bracket = rootName == null ? -1 : rootName.indexOf('[');
        return bracket <= 0 ? rootName : rootName.substring(0, bracket);
    }

    /**
     * Call display values can be shared by several source positions (for
     * example two {@code call:info(2)} nodes). Exposing such a value would
     * silently resolve to the latest occurrence, so only unique calls are
     * listed. Raw expressions are omitted because their sanitised text contains
     * identity hashes and is not a useful user-facing target.
     */
    // 作用：判断匿名值是否歧义；逻辑：同名节点多于一个时返回 true。
    private boolean isAmbiguousAnonymousValue(ValueNodeRecord node, List<ValueNodeRecord> nodes) {
        if (!"CALL_RESULT".equals(node.valueKind())) {
            return false;
        }
        long occurrences = nodes.stream()
                .filter(candidate -> candidate.scopeSymbolId().equals(node.scopeSymbolId()))
                .filter(candidate -> candidate.canonicalValue().equals(node.canonicalValue()))
                .count();
        return occurrences > 1;
    }

    // 作用：判断是否为内部版本节点；逻辑：匹配内部版本前缀。
    private boolean isInternalVersion(ValueNodeRecord node) {
        String value = node.canonicalValue();
        return value.startsWith("branch:")
                || value.startsWith("phi:")
                || value.startsWith("before:");
    }

    // 作用：判断根节点作用域是否匹配；逻辑：比较节点作用域与方法作用域。
    private boolean rootScopeMatches(
            ValueNodeRecord field,
            Map<String, ValueNodeRecord> roots
    ) {
        ValueNodeRecord root = roots.get(field.rootName());
        if (root == null) {
            root = roots.get(containerBaseRoot(field.rootName()));
        }
        /*
         * A root object is usually only read in callees: autoPay passes
         * orderRequest to buildInvoice, where the actual addressId/userIp
         * fields are read. Requiring the field declaration to live in the root
         * method scope hides every such field from targets. The resolver input
         * is already limited to reachable scopes, so matching by the root name
         * lets the user select those downstream fields without inventing paths
         * that were never observed by the analyzer.
         */
        return root != null;
    }

    // 作用：取容器根表达式；逻辑：截断元素访问后缀。
    private String containerRoot(String canonicalValue) {
        int bracket = canonicalValue == null ? -1 : canonicalValue.indexOf('[');
        return bracket <= 0 ? null : canonicalValue.substring(0, bracket);
    }

    // 作用：收集证据节点；逻辑：遍历数据边并汇总证据标识。
    private Set<String> evidenceNodeIds(List<DataEdgeRecord> edges) {
        Set<String> result = new HashSet<>();
        for (DataEdgeRecord edge : edges) {
            if (edge.dependencyOnly()) {
                continue;
            }
            result.add(edge.srcValueId());
            result.add(edge.dstValueId());
        }
        return result;
    }

    // 作用：收集 Builder 结果节点；逻辑：筛选 Builder 相关边并取目标节点。
    private Set<String> builderResultNodeIds(List<DataEdgeRecord> edges) {
        Set<String> result = new HashSet<>();
        for (DataEdgeRecord edge : edges) {
            if ("BUILDER_RESULT".equals(edge.edgeType())) {
                result.add(edge.srcValueId());
            }
        }
        return result;
    }

    // 作用：构造目标候选；逻辑：写入表达式、展示类型与解析强度。
    private TargetCandidate candidate(ValueNodeRecord root, String expression) {
        return new TargetCandidate(
                root.valueId(),
                expression,
                displayKind(root),
                root.scopeSymbolId(),
                expression
        );
    }

    // 作用：生成目标的展示类型；逻辑：按节点种类与字段名归类。
    private String displayKind(ValueNodeRecord node) {
        if ("CALL_RESULT".equals(node.valueKind()) && node.canonicalValue().startsWith("new:")) {
            return "CONSTRUCTOR_RESULT";
        }
        if ("CONSTANT".equals(node.valueKind())) {
            return "LITERAL";
        }
        return node.valueKind();
    }
}
