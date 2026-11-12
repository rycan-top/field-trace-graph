package io.fieldtracegraph.core.model;

// 作用：链路端点。
// 使用场景：标记血缘路径的起点与终点。

/**
 * A value at which a directional traversal stopped. Leaves are a presentation
 * projection of boundaries; edges and source evidence remain the source of
 * truth.
 *
 * 作用：链路端点；场景：标记“从哪开始/到哪结束”。
 */
public record TraceLeaf(
        String nodeId,
        String expression,
        String kind,
        String scopeId,
        String direction,
        String reason,
        String evidenceId,
        String sourceFile,
        int lineStart,
        int columnStart,
        int lineEnd,
        int columnEnd
) {
}
