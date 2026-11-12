package io.fieldtracegraph.cli.application;

import io.fieldtracegraph.cli.config.LineageRuntimeProperties;
import io.fieldtracegraph.cli.contract.ManualContractEvidence;
import io.fieldtracegraph.cli.contract.ProjectContractLoader;
import io.fieldtracegraph.cli.contract.ProjectContracts;
import io.fieldtracegraph.cli.project.ProjectIndexPaths;
import io.fieldtracegraph.core.model.AmbiguousCandidate;
import io.fieldtracegraph.core.model.BoundaryAction;
import io.fieldtracegraph.core.model.CallEdgeRecord;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.EdgeEvidence;
import io.fieldtracegraph.core.model.MethodSummaryRecord;
import io.fieldtracegraph.core.model.PendingRefRecord;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.SourceScanOptions;
import io.fieldtracegraph.core.model.TraceResult;
import io.fieldtracegraph.core.model.TraceWeakHop;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.port.GraphReadSession;
import io.fieldtracegraph.core.port.GraphSnapshotReader;
import io.fieldtracegraph.core.port.ProjectSourceReader;
import io.fieldtracegraph.core.query.BidirectionalLineageTracer;
import io.fieldtracegraph.core.query.CallScopeMode;
import io.fieldtracegraph.core.query.LineageGraph;
import io.fieldtracegraph.core.query.LineageQuery;
import io.fieldtracegraph.core.query.ReachableScopeCollector;
import io.fieldtracegraph.core.query.TraceBudgetPolicy;
import io.fieldtracegraph.javaanalysis.semantic.context.SemanticContextFingerprint;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// 作用：打开图快照并执行血缘追踪。
// 使用场景：trace 命令的核心查询链路。

/**
 * Read-only lineage query service. It opens one snapshot session, builds a
 * request-scoped graph, and delegates traversal to the core query engine. It
 * never initialises or synchronises the index.
 */
public class LineageQueryService {
    private final GraphSnapshotReader snapshotReader;
    private final ProjectSourceReader sourceReader;
    private final SemanticContextFingerprint contextFingerprint = new SemanticContextFingerprint();
    private final BidirectionalLineageTracer tracer = new BidirectionalLineageTracer();
    private final LineageRuntimeProperties runtimeProperties;
    private final ReachableScopeCollector scopeCollector;
    private final ProjectContractLoader contractLoader = new ProjectContractLoader();
    private final ManualContractEvidence manualContractEvidence = new ManualContractEvidence();

    // 作用：注入查询依赖；逻辑：保存快照读取器、源采集器与运行配置，并创建可达作用域收集器。
    public LineageQueryService(
            GraphSnapshotReader snapshotReader,
            ProjectSourceReader sourceReader,
            LineageRuntimeProperties runtimeProperties
    ) {
        this.snapshotReader = snapshotReader;
        this.sourceReader = sourceReader;
        this.runtimeProperties = runtimeProperties;
        this.scopeCollector = new ReachableScopeCollector(runtimeProperties.maxCalleeDepth());
    }


    // 作用：执行追踪并自适应扩边；逻辑：解析预算后单次追踪，遇到边数截断时逐步放宽上限重试。
    public TraceResult trace(
            Path root,
            String methodSelector,
            String targetExpression,
            String direction,
            int maxDepth,
            int maxEdges,
            long queryTimeoutMillis,
            SourceScanOptions options,
            CallScopeMode callScope,
            boolean includeDependencies,
            boolean strictContracts
    ) {
        io.fieldtracegraph.core.query.TraversalBudget budget = TraceBudgetPolicy.resolve(
                targetExpression, maxDepth, maxEdges, queryTimeoutMillis);
        boolean adaptive = maxEdges == -1;
        TraceResult result = traceOnce(root, methodSelector, targetExpression, direction,
                options, callScope, includeDependencies, strictContracts, budget);
        while (adaptive
                && result.truncated()
                && result.truncationReasons().stream()
                        .anyMatch(reason -> "EDGE_LIMIT".equals(reason) || "SEED_LIMIT".equals(reason))
                && budget.maxEdges() < 10_000) {
            int next = Math.min(10_000, budget.maxEdges() * 2);
            if (next <= budget.maxEdges()) {
                break;
            }
            budget = new io.fieldtracegraph.core.query.TraversalBudget(
                    budget.maxDepth(), next, budget.timeoutMillis());
            result = traceOnce(root, methodSelector, targetExpression, direction,
                    options, callScope, includeDependencies, strictContracts, budget);
        }
        return result;
    }

    // 作用：执行单次追踪；逻辑：打开快照，加载作用域内节点、边、证据与摘要，构建图后交给遍历器。
    private TraceResult traceOnce(
            Path root,
            String methodSelector,
            String targetExpression,
            String direction,
            SourceScanOptions options,
            CallScopeMode callScope,
            boolean includeDependencies,
            boolean strictContracts,
            io.fieldtracegraph.core.query.TraversalBudget budget
    ) {
        LineageQuery query = LineageQuery.of(direction, budget);
        CallScopeMode resolvedCallScope = callScope == null ? CallScopeMode.EFFECTIVE : callScope;
        try (GraphReadSession session = snapshotReader.open(ProjectIndexPaths.databasePath(root))) {
            var method = session.findMethodBySelector(methodSelector);
            if (method == null) {
                throw new IllegalArgumentException("METHOD_NOT_FOUND");
            }
            Set<String> scopeIds = collectScopes(
                    session,
                    method.methodId(),
                    resolvedCallScope,
                    direction
            );
            List<ValueNodeRecord> valueNodes = session.valueNodesForScopeIds(new ArrayList<>(scopeIds));
            List<DataEdgeRecord> dataEdges = session.dataEdgesForScopeIds(new ArrayList<>(scopeIds)).stream()
                    .filter(edge -> includeDependencies || !edge.dependencyOnly())
                    .toList();
            List<EdgeEvidence> edgeEvidence = new ArrayList<>(
                    session.edgeEvidenceForScopeIds(new ArrayList<>(scopeIds))
            );
            ProjectContracts contracts = contractLoader.load(root, strictContracts);
            edgeEvidence.addAll(manualContractEvidence.apply(
                    contracts, dataEdges, valueNodes, strictContracts));
            List<CallEdgeRecord> callEdges = session.callEdgesForScopeIds(new ArrayList<>(scopeIds));
            List<MethodSummaryRecord> methodSummaries =
                    session.methodSummariesForScopeIds(new ArrayList<>(scopeIds));
            List<PendingRefRecord> pendingRefs = session.pendingRefsForScopeIds(new ArrayList<>(scopeIds));
            Set<String> fileIds = new LinkedHashSet<>();
            for (DataEdgeRecord edge : dataEdges) {
                fileIds.add(edge.fileId());
            }
            for (CallEdgeRecord edge : callEdges) {
                fileIds.add(edge.fileId());
            }
            for (MethodSummaryRecord summary : methodSummaries) {
                fileIds.add(summary.fileId());
            }
            for (PendingRefRecord ref : pendingRefs) {
                fileIds.add(ref.fileId());
            }
            List<SourceFileRecord> scannedFiles = sourceReader.capture(root, options);
            Map<String, SourceFileRecord> sourceFiles = withSourceContent(
                    session.sourceFilesByIds(new ArrayList<>(fileIds)),
                    scannedFiles
            );
            Map<String, String> scopeQnames = session.qnamesBySymbolIds(new ArrayList<>(scopeIds));
            LineageGraph graph = new LineageGraph(
                    method,
                    session.generation(),
                    session.graphStatus(),
                    valueNodes,
                    dataEdges,
                    edgeEvidence,
                    callEdges,
                    pendingRefs,
                    methodSummaries,
                    sourceFiles,
                    scopeQnames
            );
            String freshness = computeFreshness(session, scannedFiles, root, options);
            List<AmbiguousCandidate> ambiguousCandidates = ambiguousCandidates(pendingRefs, scopeQnames);
            List<TraceWeakHop> weakHops = weakHops(pendingRefs, scopeQnames, sourceFiles);
            return tracer.trace(
                    graph,
                    targetExpression,
                    query.direction(),
                    budget,
                    freshness,
                    ambiguousCandidates,
                    weakHops
            );
        }
    }

    // 作用：收集追踪作用域；逻辑：显式模式直接按配置收集，自动模式按方向决定是否包含调用方。
    private Set<String> collectScopes(
            GraphReadSession session,
            String methodId,
            CallScopeMode callScope,
            String direction
    ) {
        if (callScope != CallScopeMode.AUTO) {
            return scopeCollector.collect(
                    methodId,
                    callScope.includesCallers(),
                    scopeId -> session.callEdgesForScopeIds(List.of(scopeId)),
                    session.callersByCallee()
            );
        }
        boolean includeCallers = "in".equals(direction) || "both".equals(direction);
        Set<String> scopes = new LinkedHashSet<>(scopeCollector.collect(
                methodId,
                includeCallers,
                scopeId -> session.callEdgesForScopeIds(List.of(scopeId)),
                session.callersByCallee()
        ));
        if ("both".equals(direction)) {
            scopes.addAll(scopeCollector.collect(
                    methodId,
                    false,
                    scopeId -> session.callEdgesForScopeIds(List.of(scopeId)),
                    session.callersByCallee()
            ));
        }
        return scopes;
    }

    // 作用：计算索引新鲜度；逻辑：比对当前文件哈希与上下文指纹是否和快照一致。
    private String computeFreshness(GraphReadSession session, List<SourceFileRecord> files,
                                    Path root, SourceScanOptions options) {
        Map<String, String> currentHashes = files.stream()
                .collect(Collectors.toMap(SourceFileRecord::relativePath, SourceFileRecord::contentHash));
        String currentContext = contextFingerprint.compute(
                files,
                runtimeProperties.parserVersion(),
                root,
                options.includeTests(),
                options.classpathEntries()
        );
        boolean fresh = currentHashes.equals(session.sourceFileHashes())
                && currentContext.equals(session.contextHash());
        return fresh ? "FRESH" : "STALE";
    }

    /**
     * The index stores source metadata; query-time evidence needs the exact
     * source text to show the raw accessor/setter expression. Reuse the scan
     * already performed for freshness instead of reading files a second time.
     */
    // 作用：补全源码内容；逻辑：用本次扫描结果覆盖索引元数据中的内容字段。
    private Map<String, SourceFileRecord> withSourceContent(
            Map<String, SourceFileRecord> indexedFiles,
            List<SourceFileRecord> scannedFiles
    ) {
        Map<String, SourceFileRecord> scannedByPath = scannedFiles.stream()
                .collect(Collectors.toMap(SourceFileRecord::relativePath, file -> file));
        Map<String, SourceFileRecord> result = new java.util.LinkedHashMap<>();
        indexedFiles.forEach((fileId, indexed) -> {
            SourceFileRecord scanned = scannedByPath.get(indexed.relativePath());
            result.put(fileId, scanned == null ? indexed : indexed.withContent(scanned.content()));
        });
        return result;
    }

    // 作用：构造歧义候选；逻辑：跳过外部边界与缺失被调方，按候选列表展开并附带边界处理建议。
    private List<AmbiguousCandidate> ambiguousCandidates(
            List<PendingRefRecord> pendingRefs,
            Map<String, String> scopeQnames
    ) {
        List<AmbiguousCandidate> result = new ArrayList<>();
        for (PendingRefRecord ref : pendingRefs) {
            if (ref.externalBoundary() || "CALLEE_SYMBOL_MISSING".equals(ref.reason())) {
                continue;
            }
            BoundaryAction action = BoundaryAction.forPendingReason(ref.reason());
            String callerQname = scopeQnames.getOrDefault(ref.callerSymbolId(), ref.callerSymbolId());
            if (ref.candidates() == null || ref.candidates().isEmpty()) {
                result.add(new AmbiguousCandidate(
                        ref.callSiteId(),
                        ref.callerSymbolId(),
                        callerQname,
                        ref.referenceName(),
                        ref.reason(),
                        ref.fileId(),
                        ref.lineStart(),
                        action.reasonCode(),
                        action.recoverable(),
                        action.suggestedAction(),
                        action.evidenceSource()
                ));
            } else {
                for (String qname : ref.candidates()) {
                    result.add(new AmbiguousCandidate(
                            ref.callSiteId(),
                            ref.callerSymbolId(),
                            callerQname,
                            qname,
                            ref.reason(),
                            ref.fileId(),
                            ref.lineStart(),
                            action.reasonCode(),
                            action.recoverable(),
                            action.suggestedAction(),
                            action.evidenceSource()
                    ));
                }
            }
        }
        return List.copyOf(result);
    }

    // 作用：构造弱跳记录；逻辑：把外部边界引用转换为带原因、可恢复性与建议动作的弱跳。
    private List<TraceWeakHop> weakHops(
            List<PendingRefRecord> pendingRefs,
            Map<String, String> scopeQnames,
            Map<String, SourceFileRecord> sourceFiles
    ) {
        List<TraceWeakHop> result = new ArrayList<>();
        for (PendingRefRecord ref : pendingRefs) {
            if (!ref.externalBoundary()) {
                continue;
            }
            BoundaryAction action = BoundaryAction.forPendingReason(ref.reason());
            result.add(new TraceWeakHop(
                    ref.callSiteId(),
                    ref.callerSymbolId(),
                    scopeQnames.getOrDefault(ref.callerSymbolId(), ref.callerSymbolId()),
                    ref.referenceName(),
                    "EXTERNAL_BOUNDARY",
                    ref.fileId(),
                    sourcePath(sourceFiles, ref.fileId()),
                    ref.lineStart(),
                    ref.columnStart(),
                    ref.lineEnd(),
                    ref.columnEnd(),
                    ref.dispatchKind(),
                    "UNRESOLVED",
                    ref.receiverValueId(),
                    ref.argumentValueIds() == null ? List.of() : ref.argumentValueIds(),
                    ref.resultValueId(),
                    action.reasonCode(),
                    action.recoverable(),
                    action.suggestedAction(),
                    action.evidenceSource()
            ));
        }
        return List.copyOf(result);
    }

    // 作用：由文件 ID 取路径；逻辑：查不到记录时返回 null。
    private String sourcePath(Map<String, SourceFileRecord> sourceFiles, String fileId) {
        SourceFileRecord file = sourceFiles.get(fileId);
        return file == null ? null : file.relativePath();
    }

}
