package io.fieldtracegraph.core.model;

import java.util.List;

// 作用：一次源码分析的完整产物，包含符号、数据边与诊断。
// 使用场景：SourceAnalyzer 分析完成后返回给索引服务统一发布。

public record AnalysisResult(
        List<SourceFileRecord> sourceFiles,
        List<SymbolRecord> symbols,
        List<ValueNodeRecord> valueNodes,
        List<DataEdgeRecord> dataEdges,
        List<CallEdgeRecord> callEdges,
        List<PendingRefRecord> pendingRefs,
        List<MethodSummaryRecord> methodSummaries,
        List<Diagnostic> diagnostics
) {
}
