package io.fieldtracegraph.javaanalysis.semantic.protocol;

import java.util.List;

// 作用：语义 worker 返回给主进程的响应。
// 使用场景：承载调用归属结果与诊断。

public record SemanticWorkerResponse(
        int schemaVersion,
        String status,
        List<SemanticWorkerDiagnostic> diagnostics,
        List<CallAttribution> attributions
) {
    public static final int SCHEMA_VERSION = 1;
}
