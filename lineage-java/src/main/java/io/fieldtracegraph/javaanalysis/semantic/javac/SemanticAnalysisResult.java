package io.fieldtracegraph.javaanalysis.semantic.javac;

import io.fieldtracegraph.core.model.Diagnostic;
import io.fieldtracegraph.javaanalysis.semantic.protocol.CallAttribution;

import java.util.List;

// 作用：承载语义分析的归属结果与诊断。
// 使用场景：worker 分析完成后回传给主进程。

public record SemanticAnalysisResult(
        List<Diagnostic> diagnostics,
        List<CallAttribution> attributions
) {
}
