package io.fieldtracegraph.cli.dto;

import io.fieldtracegraph.core.model.TraceResult;
import io.fieldtracegraph.core.query.TraceQuality;

import java.util.List;

// 作用：追踪结果的 JSON 结构。
// 使用场景：CI 或下游工具解析边、证据与质量信息。

public record TraceResultDto(
        String methodId,
        String methodQname,
        String targetId,
        String targetExpression,
        String direction,
        int indexGeneration,
        String indexStatus,
        String freshness,
        List<Object> nodes,
        List<Object> edges,
        List<Object> chains,
        List<Object> boundaries,
        List<Object> leaves,
        List<Object> ambiguousCandidates,
        List<Object> weakHops,
        List<Object> sourceEvidence,
        List<Object> fileIndexes,
        boolean snapshotConsistent,
        Object noMatchReason,
        List<Object> evidenceIssues,
        boolean truncated,
        List<String> truncationReasons,
        boolean cycleDetected,
        TraceQuality quality
) {
    // 作用：把追踪结果转为输出 DTO；逻辑：复制各列表并附加质量门禁计算结果。
    public static TraceResultDto from(TraceResult result) {
        return new TraceResultDto(
                result.methodId(),
                result.methodQname(),
                result.targetId(),
                result.targetExpression(),
                result.direction(),
                result.indexGeneration(),
                result.indexStatus(),
                result.freshness(),
                List.copyOf(result.nodes()),
                List.copyOf(result.edges()),
                List.copyOf(result.chains()),
                List.copyOf(result.boundaries()),
                List.copyOf(result.leaves()),
                List.copyOf(result.ambiguousCandidates()),
                List.copyOf(result.weakHops()),
                List.copyOf(result.sourceEvidence()),
                List.copyOf(result.fileIndexes()),
                result.snapshotConsistent(),
                result.noMatchReason(),
                List.copyOf(result.evidenceIssues()),
                result.truncated(),
                List.copyOf(result.truncationReasons()),
                result.cycleDetected(),
                TraceQuality.from(result, result.allowCandidate(), result.failOnCandidate())
        );
    }
}
