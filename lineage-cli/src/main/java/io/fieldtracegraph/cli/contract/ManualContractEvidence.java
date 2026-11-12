package io.fieldtracegraph.cli.contract;

import io.fieldtracegraph.cli.log.CliLog;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.EdgeEvidence;
import io.fieldtracegraph.core.model.Resolution;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：把人工契约转换为可入库的证据。
// 使用场景：生成 MANUAL_CONTRACT 来源的边证据。

/**
 * Adds MANUAL_CONTRACT evidence to existing logical edges. Contracts explain
 * or tighten a discovered edge; they never invent an edge that the static,
 * generated-source, or adapter layers did not find.
 */
public final class ManualContractEvidence {
    // 作用：为匹配的路径生成人工证据；逻辑：按规则端点找最短路径，逐条边附加 MANUAL_CONTRACT 证据。
    public List<EdgeEvidence> apply(
            ProjectContracts contracts,
            List<DataEdgeRecord> edges,
            List<ValueNodeRecord> nodes,
            boolean strict
    ) {
        if (contracts.contracts().isEmpty() || edges.isEmpty()) {
            return List.of();
        }
        Map<String, List<DataEdgeRecord>> outgoing = new LinkedHashMap<>();
        Map<String, String> fieldToRoot = fieldToRoot(nodes);
        for (DataEdgeRecord edge : edges) {
            outgoing.computeIfAbsent(edge.srcValueId(), key -> new ArrayList<>()).add(edge);
        }
        List<EdgeEvidence> result = new ArrayList<>();
        for (ProjectContracts.ContractRule rule : contracts.contracts()) {
            if (!valid(rule)) {
                CliLog.warn("ignored contracts.yml entry without from/to, owner, reason, and expiresWhen");
                continue;
            }
            boolean matched = false;
            List<String> sources = nodes.stream()
                    .filter(node -> matches(node, rule.from(), nodes))
                    .map(ValueNodeRecord::valueId)
                    .toList();
            List<String> targets = nodes.stream()
                    .filter(node -> matches(node, rule.to(), nodes))
                    .map(ValueNodeRecord::valueId)
                    .toList();
            int pairLimit = Math.min(20, sources.size() * targets.size());
            int pairs = 0;
            for (String sourceId : sources) {
                for (String targetId : targets) {
                    if (pairs++ >= pairLimit) {
                        break;
                    }
                    List<DataEdgeRecord> path = shortestPath(
                            sourceId,
                            targetId,
                            outgoing,
                            fieldToRoot
                    );
                    if (path.isEmpty()) {
                        continue;
                    }
                    matched = true;
                    for (DataEdgeRecord edge : path) {
                        result.add(manualEvidence(edge, rule));
                    }
                }
            }
            if (!matched) {
                String message = "contracts.yml entry did not match a discovered edge: "
                        + rule.from() + " -> " + rule.to();
                if (strict) {
                    throw new IllegalArgumentException("CONTRACT_UNMATCHED: " + message);
                }
                CliLog.warn(message);
            }
        }
        return List.copyOf(result);
    }

    // 作用：构造单条人工证据；逻辑：用逻辑边键、规则与所有者生成稳定 ID，并写入来源与位置。
    private EdgeEvidence manualEvidence(
            DataEdgeRecord edge,
            ProjectContracts.ContractRule rule
    ) {
        String evidenceId = Ids.sha256(
                edge.logicalEdgeKey(),
                "manual-contract",
                edge.edgeId(),
                rule.owner(),
                rule.from(),
                rule.to()
        );
        return new EdgeEvidence(
                evidenceId,
                edge.logicalEdgeKey(),
                edge.edgeId(),
                manualResolution(rule.confidence()).name(),
                "MANUAL_CONTRACT",
                "MANUAL_CONTRACT",
                "contracts.yml",
                rule.reason(),
                edge.fileId(),
                edge.lineStart(),
                edge.columnStart(),
                edge.lineEnd(),
                edge.columnEnd(),
                details(rule),
                "PROJECT_CONTRACT_PATH",
                null,
                edge.scopeSymbolId()
        );
    }

    /**
     * A manual contract usually names the approved endpoint fields, while the
     * graph may contain an intermediate setters/assignment edge. Attach the
     * evidence to every logical edge on the shortest discovered path instead
     * of pretending that the endpoints are adjacent.
     */
    // 作用：查找两端点间最短路径；逻辑：从源节点广度优先，允许经字段根节点跳转并限制遍历规模。
    private List<DataEdgeRecord> shortestPath(
            String sourceId,
            String targetId,
            Map<String, List<DataEdgeRecord>> outgoing,
            Map<String, String> fieldToRoot
    ) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        Map<String, String> parentNode = new HashMap<>();
        Map<String, DataEdgeRecord> parentEdge = new HashMap<>();
        queue.add(sourceId);
        visited.add(sourceId);
        int inspected = 0;
        while (!queue.isEmpty() && inspected++ < 5000) {
            String current = queue.poll();
            if (current.equals(targetId)) {
                return reconstruct(current, sourceId, parentNode, parentEdge);
            }
            for (DataEdgeRecord edge : outgoing.getOrDefault(current, List.of())) {
                String next = edge.dstValueId();
                if (visited.add(next)) {
                    parentNode.put(next, current);
                    parentEdge.put(next, edge);
                    queue.add(next);
                }
            }
            String rootId = fieldToRoot.get(current);
            if (rootId != null && visited.add(rootId)) {
                parentNode.put(rootId, current);
                queue.add(rootId);
            }
        }
        return List.of();
    }

    // 作用：回溯最短路径；逻辑：沿父节点反向收集边，再翻转得到正序路径。
    private List<DataEdgeRecord> reconstruct(
            String targetId,
            String sourceId,
            Map<String, String> parentNode,
            Map<String, DataEdgeRecord> parentEdge
    ) {
        List<DataEdgeRecord> result = new ArrayList<>();
        String current = targetId;
        while (!current.equals(sourceId)) {
            String previous = parentNode.get(current);
            if (previous == null) {
                return List.of();
            }
            DataEdgeRecord edge = parentEdge.get(current);
            if (edge != null) {
                result.add(edge);
            }
            current = previous;
        }
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }

    // 作用：构建字段到根节点映射；逻辑：按作用域与规范值索引根节点，再关联各字段访问路径。
    private Map<String, String> fieldToRoot(List<ValueNodeRecord> nodes) {
        Map<String, ValueNodeRecord> roots = new HashMap<>();
        for (ValueNodeRecord node : nodes) {
            if (node.accessPath() == null || node.accessPath().isEmpty()) {
                roots.put(node.scopeSymbolId() + "\u001f" + node.canonicalValue(), node);
            }
        }
        Map<String, String> result = new HashMap<>();
        for (ValueNodeRecord node : nodes) {
            if (node.accessPath() == null || node.accessPath().isEmpty()
                    || node.rootName() == null) {
                continue;
            }
            ValueNodeRecord root = roots.get(node.scopeSymbolId() + "\u001f" + node.rootName());
            if (root != null) {
                result.put(node.valueId(), root.valueId());
            }
        }
        return result;
    }

    // 作用：校验规则完整性；逻辑：要求端点、所有者、原因与失效条件均非空。
    private boolean valid(ProjectContracts.ContractRule rule) {
        return rule.from() != null && !rule.from().isBlank()
                && rule.to() != null && !rule.to().isBlank()
                && rule.owner() != null && !rule.owner().isBlank()
                && rule.reason() != null && !rule.reason().isBlank()
                && rule.expiresWhen() != null && !rule.expiresWhen().isBlank();
    }

    // 作用：把契约置信度转为解析级别；逻辑：CANDIDATE 保持候选，其余视为精确静态。
    private Resolution manualResolution(String confidence) {
        if ("CANDIDATE".equalsIgnoreCase(confidence)) {
            return Resolution.CANDIDATE;
        }
        return Resolution.EXACT_STATIC;
    }

    // 作用：判断节点是否匹配选择器；逻辑：支持规范值、根名加访问路径与根值加字段路径三种写法。
    private boolean matches(
            ValueNodeRecord node,
            String selector,
            List<ValueNodeRecord> allNodes
    ) {
        if (selector == null || selector.isBlank()) {
            return false;
        }
        if (selector.equals(node.canonicalValue())) {
            return true;
        }
        if (node.rootName() != null && node.accessPath() != null && !node.accessPath().isEmpty()) {
            return selector.equals(node.rootName() + "." + String.join(".", node.accessPath()));
        }
        int dot = selector.indexOf('.');
        if (dot > 0
                && selector.substring(0, dot).equals(node.canonicalValue())
                && (node.accessPath() == null || node.accessPath().isEmpty())) {
            List<String> fields = List.of(selector.substring(dot + 1).split("\\."));
            return allNodes.stream().anyMatch(candidate ->
                    node.scopeSymbolId().equals(candidate.scopeSymbolId())
                            && fields.equals(candidate.accessPath())
            );
        }
        return false;
    }

    // 作用：序列化证据明细；逻辑：把所有者、失效条件与置信度拼成转义后的 JSON。
    private String details(ProjectContracts.ContractRule rule) {
        return "{\"owner\":\"" + escape(rule.owner()) + "\",\"expiresWhen\":\""
                + escape(rule.expiresWhen() == null ? "" : rule.expiresWhen())
                + "\",\"confidence\":\"" + escape(rule.confidence()) + "\"}";
    }

    // 作用：转义 JSON 字符；逻辑：处理反斜杠、引号、换行与回车。
    private String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
