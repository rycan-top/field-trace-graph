package io.fieldtracegraph.core.model;

// 作用：查询结果图中的节点。
// 使用场景：trace 的 JSON 结构化输出。

// 作用：结果图节点与边；场景：JSON 结构化输出。
public record TraceNode(
        String nodeId,
        String scopeId,
        String scopeQname,
        String displayName,
        String kind,
        int lineStart,
        boolean internal
) {
}
