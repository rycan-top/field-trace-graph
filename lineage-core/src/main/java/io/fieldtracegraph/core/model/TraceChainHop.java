package io.fieldtracegraph.core.model;

// 作用：链路中的单跳信息。
// 使用场景：链路展示时关联每一步的边、文件与证据。

/**
 * Self-contained description of one hop in a projected lineage chain. The
 * edge collection remains the source of truth; this record lets consumers
 * render a chain without joining every edge id back to that collection.
 *
 * 作用：组装后的可读链路与单跳；场景：text 输出的链路展示。
 */
public record TraceChainHop(
        String edgeId,
        String srcNodeId,
        String dstNodeId,
        String edgeType,
        String operation,
        String resolution,
        boolean resolved,
        boolean bridged,
        String direction,
        String sourceFile,
        int lineStart,
        int lineEnd
) {
}
