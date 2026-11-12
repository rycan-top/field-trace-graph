package io.fieldtracegraph.core.model;

// 作用：查询结果中的开放边界。
// 使用场景：trace 输出为什么不确定、是否可恢复以及建议动作。

public record TraceBoundary(
        String nodeId,
        String scopeId,
        String direction,
        String reason,
        String evidenceId,
        String expression,
        String kind,
        String sourceFile,
        int lineStart,
        int columnStart,
        int lineEnd,
        int columnEnd,
        String reasonCode,
        boolean recoverable,
        String suggestedAction,
        String evidenceSource
) {
}
