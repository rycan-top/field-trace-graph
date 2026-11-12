package io.fieldtracegraph.core.model;

// 作用：方法内可追踪目标的候选。
// 使用场景：targets 命令列出目标表达式与置信信息。

public record TargetCandidate(
        String targetId,
        String targetExpression,
        String kind,
        String scopeId,
        String displayName
) {
}
