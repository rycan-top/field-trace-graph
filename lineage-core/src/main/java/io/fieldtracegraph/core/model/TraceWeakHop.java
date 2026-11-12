package io.fieldtracegraph.core.model;

import java.util.List;

// 作用：证据不足的跨调用跳。
// 使用场景：--max-weak-hops 预算控制与结果质量提示。

/**
 * A call site that is visible in source but cannot be bridged into another
 * analysed method. External library calls and unresolved receivers belong
 * here; they are explicit boundaries, not ambiguous overload candidates.
 *
 */
public record TraceWeakHop(
        String callSiteId,
        String callerSymbolId,
        String callerQname,
        String targetExpression,
        String reason,
        String fileId,
        String sourceFile,
        int lineStart,
        int columnStart,
        int lineEnd,
        int columnEnd,
        String dispatchKind,
        String resolution,
        String receiverValueId,
        List<String> argumentValueIds,
        String resultValueId,
        String reasonCode,
        boolean recoverable,
        String suggestedAction,
        String evidenceSource
) {
}
