package io.fieldtracegraph.core.query;

import io.fieldtracegraph.core.model.TraceEdge;
import io.fieldtracegraph.core.support.Ids;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// 作用：遍历过程中的访问状态与结果收集。
// 使用场景：去重、防止环路与汇总最终路径。

/**
 * Mutable, request-scoped traversal accumulator shared by the forward and
 * backward traversals so both directions share one edge budget and one
 * truncation contract. Each direction keeps its own frontier so a bidirectional
 * query is the union of two cones rather than an unbounded walk over the whole
 * connected component.
 */
final class TraversalState {
    /**
     * Union of every value reached by either direction. The result merger needs
     * the union, while each direction must keep a separate visited set.
     */
    final Set<ValueRef> visited = new LinkedHashSet<>();
    /**
     * Values reached while walking towards the producers. Every entry is on an
     * upstream path by construction, so the result merger must not discard them
     * merely because their reverse edge cannot be regenerated from the target
     * side (for example a field read that becomes a nested field path).
     */
    final Set<ValueRef> backwardVisited = new LinkedHashSet<>();
    final List<TraceEdge> edges = new ArrayList<>();
    final Set<String> seenContextEdgeKeys = new LinkedHashSet<>();
    final Set<String> seenLogicalEdgeKeys = new LinkedHashSet<>();
    final List<String> truncationReasons = new ArrayList<>();
    long deadline;
    boolean truncated;

    // 作用：初始化遍历状态；逻辑：记录截止时间并准备访问集合。
    TraversalState(long deadline) {
        this.deadline = deadline;
    }

    // 作用：记录截断原因；逻辑：把原因加入集合并标记结果已被截断。
    void markTruncated(String reason) {
        truncated = true;
        if (!truncationReasons.contains(reason)) {
            truncationReasons.add(reason);
        }
    }

    /**
     * Call-context variants are required while traversing a shared callee, but
     * they represent the same physical evidence in the result. Record one
     * canonical edge per evidence, and do not let context-only copies consume
     * the user-visible edge budget.
     */
    // 作用：尝试记录一条结果边；逻辑：规范化端点、检查预算与去重，成功后加入结果集。
    boolean recordEdge(TraceEdge edge, TraversalBudget budget) {
        String contextKey = edge.edgeId() + "|" + edge.direction();
        if (seenContextEdgeKeys.contains(contextKey)) {
            return true;
        }

        String srcId = ValueRef.withoutCallContext(edge.srcId());
        String dstId = ValueRef.withoutCallContext(edge.dstId());
        String logicalKey = srcId + "|" + dstId + "|" + edge.edgeType()
                + "|" + edge.direction() + "|" + edge.evidenceId();
        if (!seenLogicalEdgeKeys.contains(logicalKey)) {
            if (edges.size() >= budget.maxEdges()) {
                markTruncated("EDGE_LIMIT");
                return false;
            }
            seenLogicalEdgeKeys.add(logicalKey);
            edges.add(canonicalEdge(edge, srcId, dstId));
        }
        seenContextEdgeKeys.add(contextKey);
        return true;
    }

    // 作用：规范化边的端点方向；逻辑：按源目标排序，保证同一逻辑边只出现一次。
    private TraceEdge canonicalEdge(TraceEdge edge, String srcId, String dstId) {
        return new TraceEdge(
                Ids.sha256(srcId, dstId, edge.edgeType(), edge.evidenceId()),
                srcId,
                dstId,
                edge.edgeType(),
                edge.operation(),
                edge.direction(),
                edge.resolution(),
                edge.evidenceId(),
                edge.resolved(),
                edge.bridged(),
                edge.srcScope(),
                edge.dstScope(),
                edge.sourceFile(),
                edge.lineStart(),
                edge.lineEnd(),
                edge.effectLocation(),
                edge.depth(),
                edge.argIndex(),
                edge.argLevel(),
                edge.category(),
                edge.provenance(),
                edge.evidence()
        );
    }
}
