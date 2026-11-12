package io.fieldtracegraph.core.model;

// 作用：重载消歧失败时的候选集合。
// 使用场景：方法调用无法唯一确定时，作为 OVERLOAD_AMBIGUOUS 边界的一部分输出。
public record AmbiguousCandidate(
        String callSiteId,
        String callerSymbolId,
        String callerQname,
        String methodQname,
        String reason,
        String fileId,
        int lineStart,
        String reasonCode,
        boolean recoverable,
        String suggestedAction,
        String evidenceSource
) {
}
