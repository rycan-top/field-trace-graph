package io.fieldtracegraph.core.model;

import java.util.List;

// 作用：暂时无法解析的引用记录。
// 使用场景：写入 pending_parse_invoke_ref 表，后续补充 classpath 或生成源码时回填。

/**
 * 作用：暂未解析的引用；场景：`pending_parse_invoke_ref` 表，后续补证时回填。
 *
 * @param pendingId
 * @param callSiteId
 * @param callerSymbolId
 * @param referenceKind
 * @param referenceName
 * @param candidates
 * @param reason
 * @param fileId
 * @param lineStart
 * @param columnStart
 * @param lineEnd
 * @param columnEnd
 * @param dispatchKind
 * @param receiverValueId
 * @param argumentValueIds
 * @param resultValueId
 */
public record PendingRefRecord(
        String pendingId,
        String callSiteId,
        String callerSymbolId,
        String referenceKind,
        String referenceName,
        List<String> candidates,
        String reason,
        String fileId,
        int lineStart,
        int columnStart,
        int lineEnd,
        int columnEnd,
        String dispatchKind,
        String receiverValueId,
        List<String> argumentValueIds,
        String resultValueId
) {
    private static final List<String> EXTERNAL_REASONS = List.of(
            "EXTERNAL",
            "EXTERNAL_LIBRARY",
            "PLATFORM_LIBRARY",
            "RPC_SERIALIZATION",
            "RPC_BOUNDARY",
            "SERIALIZATION_BOUNDARY",
            "CLASSPATH_MISSING",
            "CALLEE_SYMBOL_MISSING",
            "GENERATED_CODE_MISSING",
            "RECEIVER_TYPE_UNKNOWN",
            "DYNAMIC_SQL",
            "DYNAMIC_PROXY",
            "AOP",
            "REFLECTION"
    );

    // 作用：判断该引用是否属于外部边界；逻辑：原因码命中外部原因集合时返回 true。
    public boolean externalBoundary() {
        return reason != null && EXTERNAL_REASONS.contains(reason);
    }

    /**
     * Rebuilds the call-edge facts that the unresolved reference still owes to
     * the graph. Pending references are the only home of unresolved calls, so
     * the reader needs the call-site payload that {@code parsed_invoke_edge} no
     * longer stores for them.
     */
    // 作用：把待解析引用转成未解析调用边；逻辑：用给定边 ID 与目标方法名构造调用边，并保留位置与原因。
    public CallEdgeRecord toUnresolvedCallEdge(String edgeId, String targetQname) {
        return new CallEdgeRecord(
                edgeId,
                callSiteId,
                callerSymbolId,
                null,
                targetQname,
                dispatchKind == null || dispatchKind.isBlank() ? "VIRTUAL" : dispatchKind,
                "UNRESOLVED",
                receiverValueId,
                argumentValueIds == null ? List.of() : argumentValueIds,
                resultValueId,
                fileId,
                lineStart,
                columnStart,
                lineEnd,
                columnEnd
        );
    }
}
