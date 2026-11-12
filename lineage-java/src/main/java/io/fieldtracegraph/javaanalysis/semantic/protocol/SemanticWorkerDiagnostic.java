package io.fieldtracegraph.javaanalysis.semantic.protocol;

// 作用：语义 worker 产生的诊断信息。
// 使用场景：语义分析失败或降级时向主进程说明原因。

public record SemanticWorkerDiagnostic(
        String code,
        String message,
        String relativePath,
        Integer line
) {
}
