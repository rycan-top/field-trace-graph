package io.fieldtracegraph.core.model;

import java.util.List;

// 作用：一次血缘追踪的完整结果。
// 使用场景：trace 命令输出，并作为候选边、弱跳与质量门禁的判断依据。

public record TraceResult(
        String methodId,
        String methodQname,
        String targetId,
        String targetExpression,
        String direction,
        int indexGeneration,
        String indexStatus,
        String freshness,
        List<TraceNode> nodes,
        List<TraceEdge> edges,
        List<TraceChain> chains,
        List<TraceBoundary> boundaries,
        List<TraceLeaf> leaves,
        List<AmbiguousCandidate> ambiguousCandidates,
        List<TraceWeakHop> weakHops,
        List<SourceEvidence> sourceEvidence,
        List<TraceFileEvidence> fileIndexes,
        boolean snapshotConsistent,
        TraceNoMatchReason noMatchReason,
        List<TraceEvidenceIssue> evidenceIssues,
        boolean truncated,
        List<String> truncationReasons,
        boolean cycleDetected,
        boolean allowCandidate,
        boolean failOnCandidate
) {
    // 作用：构造不带质量策略的兼容结果；逻辑：允许候选与失败标记都置为 false 后转调完整构造器。
    public TraceResult(
            String methodId,
            String methodQname,
            String targetId,
            String targetExpression,
            String direction,
            int indexGeneration,
            String indexStatus,
            String freshness,
            List<TraceNode> nodes,
            List<TraceEdge> edges,
            List<TraceChain> chains,
            List<TraceBoundary> boundaries,
            List<TraceLeaf> leaves,
            List<AmbiguousCandidate> ambiguousCandidates,
            List<TraceWeakHop> weakHops,
            List<SourceEvidence> sourceEvidence,
            List<TraceFileEvidence> fileIndexes,
            boolean snapshotConsistent,
            TraceNoMatchReason noMatchReason,
            List<TraceEvidenceIssue> evidenceIssues,
            boolean truncated,
            List<String> truncationReasons,
            boolean cycleDetected
    ) {
        this(methodId, methodQname, targetId, targetExpression, direction,
                indexGeneration, indexStatus, freshness, nodes, edges, chains,
                boundaries, leaves, ambiguousCandidates, weakHops, sourceEvidence,
                fileIndexes, snapshotConsistent, noMatchReason, evidenceIssues,
                truncated, truncationReasons, cycleDetected, false, false);
    }

    // 作用：写入质量门禁策略结果；逻辑：复制结果并更新是否允许候选、是否因候选失败。
    public TraceResult withQualityPolicy(boolean allow, boolean failOnAnyCandidate) {
        return new TraceResult(
                methodId,
                methodQname,
                targetId,
                targetExpression,
                direction,
                indexGeneration,
                indexStatus,
                freshness,
                nodes,
                edges,
                chains,
                boundaries,
                leaves,
                ambiguousCandidates,
                weakHops,
                sourceEvidence,
                fileIndexes,
                snapshotConsistent,
                noMatchReason,
                evidenceIssues,
                truncated,
                truncationReasons,
                cycleDetected,
                allow,
                failOnAnyCandidate
        );
    }
}
