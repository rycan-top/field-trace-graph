package io.fieldtracegraph.javaanalysis;

import io.fieldtracegraph.core.model.CallEdgeRecord;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.Diagnostic;
import io.fieldtracegraph.core.model.MethodSummaryRecord;
import io.fieldtracegraph.core.model.PendingRefRecord;
import io.fieldtracegraph.core.model.AnalysisResult;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.SymbolRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;

import java.util.ArrayList;
import java.util.List;

// 作用：收集分析过程中的符号、边与诊断。
// 使用场景：所有提取器写入的统一出口，最终汇总为分析结果。

public final class JavaAnalysisAccumulator {
    private final List<SymbolRecord> symbols = new ArrayList<>();
    private final List<ValueNodeRecord> valueNodes = new ArrayList<>();
    private final List<DataEdgeRecord> dataEdges = new ArrayList<>();
    private final List<CallEdgeRecord> callEdges = new ArrayList<>();
    private final List<PendingRefRecord> pendingRefs = new ArrayList<>();
    private final List<MethodSummaryRecord> methodSummaries = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    // 作用：记录一个符号；逻辑：追加到符号列表。
    public void addSymbol(SymbolRecord symbol) {
        symbols.add(symbol);
    }

    // 作用：记录一个值节点；逻辑：追加到值节点列表。
    public void addValueNode(ValueNodeRecord node) {
        valueNodes.add(node);
    }

    // 作用：记录一条数据边；逻辑：追加到数据边列表。
    public void addDataEdge(DataEdgeRecord edge) {
        dataEdges.add(edge);
    }

    // 作用：记录一条调用边；逻辑：追加到调用边列表。
    public void addCallEdge(CallEdgeRecord edge) {
        callEdges.add(edge);
    }

    // 作用：记录一条待解析引用；逻辑：追加到待解析引用列表。
    public void addPendingRef(PendingRefRecord ref) {
        pendingRefs.add(ref);
    }

    // 作用：记录一条方法摘要；逻辑：追加到摘要列表。
    public void addMethodSummary(MethodSummaryRecord summary) {
        methodSummaries.add(summary);
    }

    // 作用：记录一条诊断；逻辑：追加到诊断列表。
    public void addDiagnostic(Diagnostic diagnostic) {
        diagnostics.add(diagnostic);
    }

    // 作用：批量记录诊断；逻辑：把新诊断整体追加到诊断列表。
    public void addDiagnostics(List<Diagnostic> newDiagnostics) {
        diagnostics.addAll(newDiagnostics);
    }

    // 作用：读取符号集合；逻辑：返回不可变副本。
    public List<SymbolRecord> symbols() {
        return List.copyOf(symbols);
    }

    // 作用：读取值节点集合；逻辑：返回不可变副本。
    public List<ValueNodeRecord> valueNodes() {
        return List.copyOf(valueNodes);
    }

    // 作用：读取数据边集合；逻辑：返回不可变副本。
    public List<DataEdgeRecord> dataEdges() {
        return List.copyOf(dataEdges);
    }

    // 作用：读取调用边集合；逻辑：返回不可变副本。
    public List<CallEdgeRecord> callEdges() {
        return List.copyOf(callEdges);
    }

    // 作用：读取待解析引用集合；逻辑：返回不可变副本。
    public List<PendingRefRecord> pendingRefs() {
        return List.copyOf(pendingRefs);
    }

    // 作用：读取方法摘要集合；逻辑：返回不可变副本。
    public List<MethodSummaryRecord> methodSummaries() {
        return List.copyOf(methodSummaries);
    }

    // 作用：读取诊断集合；逻辑：返回不可变副本。
    public List<Diagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    // 作用：汇总分析结果；逻辑：把源文件与各类收集结果组装为不可变分析结果。
    public AnalysisResult toResult(List<SourceFileRecord> sourceFiles) {
        return new AnalysisResult(
                List.copyOf(sourceFiles),
                List.copyOf(symbols),
                List.copyOf(valueNodes),
                List.copyOf(dataEdges),
                List.copyOf(callEdges),
                List.copyOf(pendingRefs),
                List.copyOf(methodSummaries),
                List.copyOf(diagnostics)
        );
    }

    // 作用：判断是否存在阻塞诊断；逻辑：任一条阻塞诊断即返回 true。
    public boolean hasBlockingDiagnostics() {
        return diagnostics.stream().anyMatch(Diagnostic::blocking);
    }
}
