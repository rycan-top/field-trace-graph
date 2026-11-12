package io.fieldtracegraph.core.model;

import java.util.List;

// 作用：方法调用边记录。
// 使用场景：跨方法字段追踪、可达范围计算与调用图展示。

public record CallEdgeRecord(
        String edgeId,
        String callSiteId,
        String callerSymbolId,
        String calleeSymbolId,
        String targetQname,
        String dispatchKind,
        String resolution,
        String receiverValueId,
        List<String> argumentValueIds,
        String resultValueId,
        String fileId,
        int lineStart,
        int columnStart,
        int lineEnd,
        int columnEnd
) {
}
