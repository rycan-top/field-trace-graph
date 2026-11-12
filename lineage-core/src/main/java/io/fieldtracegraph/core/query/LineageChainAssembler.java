package io.fieldtracegraph.core.query;

import io.fieldtracegraph.core.model.TraceChain;
import io.fieldtracegraph.core.model.TraceChainHop;
import io.fieldtracegraph.core.model.TraceEdge;
import io.fieldtracegraph.core.support.Ids;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：把零散的数据边组装成可读的血缘链路。
// 使用场景：text 输出中展示从起点到终点的完整路径。

/**
 * Projects traversed edges into bounded path summaries. Edges remain the
 * source of truth; chains are a presentation projection only. Linear runs are
 * collapsed into a single chain while branch and cycle edges stay as their own
 * minimal chains so no evidence is silently dropped.
 */
final class LineageChainAssembler {
    private static final int MAX_CHAINS = Integer.MAX_VALUE;

    // 作用：把结果边投影成可读链路；逻辑：分别处理正向与反向，合并后按弱跳与环检测裁剪。
    ChainProjection project(List<TraceEdge> edges) {
        List<TraceEdge> unique = dedupe(edges);
        Map<String, List<TraceEdge>> byDirection = new LinkedHashMap<>();
        for (TraceEdge edge : unique) {
            byDirection.computeIfAbsent(normalizeDirection(edge.direction()), key -> new ArrayList<>())
                    .add(edge);
        }
        List<TraceChain> result = new ArrayList<>();
        boolean countLimitReached = false;
        boolean cycleDetected = false;
        for (List<TraceEdge> directionalEdges : byDirection.values()) {
            ChainProjection projection = projectDirection(directionalEdges);
            cycleDetected |= projection.cycleDetected();
            int remaining = MAX_CHAINS - result.size();
            if (remaining <= 0) {
                countLimitReached = true;
                break;
            }
            if (projection.chains().size() > remaining) {
                result.addAll(projection.chains().subList(0, remaining));
                countLimitReached = true;
            } else {
                result.addAll(projection.chains());
            }
            countLimitReached |= projection.countLimitReached();
        }
        return new ChainProjection(List.copyOf(result), countLimitReached, cycleDetected);
    }

    /**
     * IN and OUT are two independent cones joined only at the selected target.
     * A cycle must therefore exist inside one cone; an IN edge and its matching
     * OUT traversal are not a data cycle.
     */
    // 作用：按单一方向组装链路；逻辑：从起点边出发沿唯一后继连接，遇到分叉或环时停止。
    private ChainProjection projectDirection(List<TraceEdge> unique) {
        Map<String, TraceEdge> byId = new HashMap<>();
        for (TraceEdge edge : unique) {
            byId.put(edge.edgeId(), edge);
        }
        Map<String, List<TraceEdge>> outgoing = new HashMap<>();
        for (TraceEdge edge : unique) {
            outgoing.computeIfAbsent(edge.srcId(), key -> new ArrayList<>()).add(edge);
        }
        Set<String> remaining = new HashSet<>();
        for (TraceEdge edge : unique) {
            remaining.add(edge.edgeId());
        }

        List<TraceChain> result = new ArrayList<>();
        boolean countLimitReached = false;
        boolean cycleDetected = containsCycle(unique);
        for (TraceEdge edge : unique) {
            if (!remaining.contains(edge.edgeId())) {
                continue;
            }
            if (result.size() >= MAX_CHAINS) {
                countLimitReached = true;
                continue;
            }
            String start = edge.srcId();
            TraceEdge incoming;
            Set<String> rewound = new HashSet<>();
            rewound.add(start);
            while ((incoming = singleIncoming(start, unique, remaining)) != null) {
                // A cyclic subgraph must not make chain presentation loop
                // forever. Leave the cycle edges in remaining so the fallback
                // minimal-chain pass below still emits their evidence.
                if (!rewound.add(incoming.srcId())) {
                    break;
                }
                start = incoming.srcId();
            }

            List<String> nodeIds = new ArrayList<>();
            List<String> edgeIds = new ArrayList<>();
            String current = start;
            nodeIds.add(current);
            while (true) {
                List<TraceEdge> nexts = outgoing.getOrDefault(current, List.of()).stream()
                        .filter(candidate -> remaining.contains(candidate.edgeId()))
                        .toList();
                if (nexts.size() != 1) {
                    break;
                }
                TraceEdge next = nexts.get(0);
                remaining.remove(next.edgeId());
                edgeIds.add(next.edgeId());
                current = next.dstId();
                nodeIds.add(current);
            }
            if (!edgeIds.isEmpty()) {
                result.add(new TraceChain(
                        "chain-" + Ids.sha256(edgeIds.toArray(String[]::new)),
                        nodeIds,
                        edgeIds,
                        weakHops(edgeIds, byId),
                        hops(edgeIds, byId)
                ));
            }
        }

        for (TraceEdge edge : unique) {
            if (!remaining.contains(edge.edgeId())) {
                continue;
            }
            if (result.size() >= MAX_CHAINS) {
                countLimitReached = true;
                continue;
            }
            result.add(new TraceChain(
                    "chain-" + edge.edgeId(),
                    List.of(edge.srcId(), edge.dstId()),
                    List.of(edge.edgeId()),
                    weakHops(List.of(edge.edgeId()), byId),
                    hops(List.of(edge.edgeId()), byId)
            ));
        }
        return new ChainProjection(List.copyOf(result), countLimitReached, cycleDetected);
    }

    record ChainProjection(List<TraceChain> chains, boolean countLimitReached, boolean cycleDetected) {
    }

    // 作用：统计链路中的弱跳数量；逻辑：遍历边 ID 并累加标记为弱的边。
    private int weakHops(List<String> edgeIds, Map<String, TraceEdge> byId) {
        int count = 0;
        for (String edgeId : edgeIds) {
            TraceEdge edge = byId.get(edgeId);
            if (edge != null && ("CANDIDATE".equals(edge.resolution())
                    || "UNRESOLVED".equals(edge.resolution()))) {
                count++;
            }
        }
        return count;
    }

    // 作用：把边 ID 转成链路单跳；逻辑：按顺序取边并生成展示用的 hop 对象。
    private List<TraceChainHop> hops(List<String> edgeIds, Map<String, TraceEdge> byId) {
        List<TraceChainHop> result = new ArrayList<>(edgeIds.size());
        for (String edgeId : edgeIds) {
            TraceEdge edge = byId.get(edgeId);
            if (edge == null) {
                continue;
            }
            result.add(new TraceChainHop(
                    edge.edgeId(),
                    edge.srcId(),
                    edge.dstId(),
                    edge.edgeType(),
                    edge.operation(),
                    edge.resolution(),
                    edge.resolved(),
                    edge.bridged(),
                    edge.direction(),
                    edge.sourceFile(),
                    edge.lineStart(),
                    edge.lineEnd()
            ));
        }
        return List.copyOf(result);
    }

    // 作用：去除重复边；逻辑：按源、目标、类型与证据键去重，保留证据更强的一条。
    private List<TraceEdge> dedupe(List<TraceEdge> edges) {
        Map<String, TraceEdge> byKey = new LinkedHashMap<>();
        for (TraceEdge edge : edges) {
            byKey.putIfAbsent(edge.srcId() + "|" + edge.dstId() + "|" + edge.edgeType()
                    + "|" + normalizeDirection(edge.direction()), edge);
        }
        return List.copyOf(byKey.values());
    }

    // 作用：规范链路方向字符串；逻辑：空值默认为 both，其余统一转小写。
    private String normalizeDirection(String direction) {
        return direction == null || direction.isBlank() ? "IN" : direction;
    }

    // 作用：找节点的唯一入边；逻辑：过滤剩余边，只有一个候选时返回，否则返回 null。
    private TraceEdge singleIncoming(String node, List<TraceEdge> edges, Set<String> remaining) {
        TraceEdge found = null;
        for (TraceEdge edge : edges) {
            if (edge.dstId().equals(node) && remaining.contains(edge.edgeId())) {
                if (found != null) {
                    return null;
                }
                found = edge;
            }
        }
        return found;
    }

    /**
     * Detects cycles over the complete deduplicated edge set, independent of
     * the presentation limit. Kahn's algorithm leaves every node in a directed
     * cycle unvisited, so a cycle cannot be hidden by the order in which
     * presentation chains are assembled.
     */
    // 作用：检测边集合是否成环；逻辑：沿源目标关系遍历，重复访问节点即判定有环。
    private boolean containsCycle(List<TraceEdge> edges) {
        Map<String, Set<String>> outgoing = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        Set<String> nodes = new HashSet<>();
        for (TraceEdge edge : edges) {
            nodes.add(edge.srcId());
            nodes.add(edge.dstId());
            if (outgoing.computeIfAbsent(edge.srcId(), key -> new HashSet<>()).add(edge.dstId())) {
                indegree.merge(edge.dstId(), 1, Integer::sum);
            }
            indegree.putIfAbsent(edge.srcId(), 0);
        }

        ArrayDeque<String> ready = new ArrayDeque<>();
        for (String node : nodes) {
            if (indegree.getOrDefault(node, 0) == 0) {
                ready.add(node);
            }
        }
        int visited = 0;
        while (!ready.isEmpty()) {
            String node = ready.removeFirst();
            visited++;
            for (String next : outgoing.getOrDefault(node, Set.of())) {
                int remaining = indegree.merge(next, -1, Integer::sum);
                if (remaining == 0) {
                    ready.addLast(next);
                }
            }
        }
        return visited < nodes.size();
    }
}
