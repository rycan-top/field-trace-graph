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
import java.util.LinkedHashMap;
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
    private static final long SOURCE_SCAN_TTL_MILLIS = 5 * 60 * 1000L;
    private static final Set<String> EXPANDED_FACTORY_TYPES = Set.of(
            "Pair", "ImmutablePair", "MutablePair",
            "Triple", "ImmutableTriple", "MutableTriple",
            "KeyValue", "Quartet", "Quintet", "Sextet", "Septet", "Octet",
            "Ennead", "Decade", "Tuple", "Tuples",
            "Tuple2", "Tuple3", "Tuple4", "Tuple5", "Tuple6", "Tuple7", "Tuple8",
            "List", "Set", "Map", "ImmutableMap", "ImmutableList", "ImmutableSet",
            "ImmutableSortedSet", "ImmutableSortedMap", "Collections", "Arrays",
            "Lists", "Sets", "Maps", "Optional", "OptionalInt", "OptionalLong",
            "OptionalDouble", "CompletableFuture", "CompletionStage", "Future",
            "AtomicReference"
    );
    private static final Set<String> EXPANDED_FACTORY_METHODS = Set.of(
            "of", "with", "entry", "asList", "singletonList", "singleton",
            "unmodifiableList", "unmodifiableSet", "unmodifiableMap", "copyOf",
            "ofNullable", "newArrayList", "newLinkedList", "newHashSet",
            "newLinkedHashSet", "newHashMap", "newLinkedHashMap", "newTreeMap",
            "newTreeSet", "ofEntries", "singletonMap", "emptyList", "emptySet",
            "emptyMap", "orElse", "orElseGet", "orElseThrow", "join", "get",
            "getNow", "completedFuture", "supplyAsync", "runAsync"
    );
    private static final Set<String> EXPANDED_WRAPPER_METHODS = Set.of(
            "map", "flatMap", "filter", "thenApply", "thenCompose", "handle",
            "ifPresent", "ifPresentOrElse", "thenAccept", "thenRun", "whenComplete"
    );
    private static final Set<String> EXPANDED_WRAPPER_TYPES = Set.of(
            "Optional", "OptionalInt", "OptionalLong", "OptionalDouble",
            "CompletableFuture", "CompletionStage", "Future", "AtomicReference"
    );
    private static final Set<String> EXPANDED_UTILITY_TYPES = Set.of(
            "StringUtils", "CollectionUtils", "ObjectUtils", "NumberUtils",
            "BooleanUtils", "ArrayUtils", "MapUtils", "Objects"
    );
    private static final Set<String> EXPANDED_CONSTRUCTOR_TYPES = Set.of(
            "ArrayList", "LinkedList", "Vector", "Stack", "ArrayDeque",
            "PriorityQueue", "CopyOnWriteArrayList", "HashSet", "LinkedHashSet",
            "TreeSet", "HashMap", "LinkedHashMap", "TreeMap", "Hashtable",
            "WeakHashMap", "EnumMap", "IdentityHashMap", "ConcurrentHashMap"
    );
    private final GraphSnapshotReader snapshotReader;
    private final ProjectSourceReader sourceReader;
    private final SemanticContextFingerprint contextFingerprint = new SemanticContextFingerprint();
    private final BidirectionalLineageTracer tracer = new BidirectionalLineageTracer();
    private final LineageRuntimeProperties runtimeProperties;
    private final ReachableScopeCollector scopeCollector;
    private final ProjectContractLoader contractLoader = new ProjectContractLoader();
    private final ManualContractEvidence manualContractEvidence = new ManualContractEvidence();
    private Path capturedRoot;
    private SourceScanOptions capturedOptions;
    private long capturedAtMillis;
    private List<SourceFileRecord> capturedFiles;
    /*
     * Batch audits ask one question per field, and a method with N fields
     * repeats the same cone load N times. The cone depends only on the scope
     * set and the index generation, so a two-entry cache removes the repeated
     * node/edge/evidence reads without changing what any single trace sees.
     */
    private final Map<String, ConeSnapshot> coneCache =
            new LinkedHashMap<>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ConeSnapshot> eldest) {
                    return size() > 2;
                }
            };

    private record ConeSnapshot(
            List<ValueNodeRecord> valueNodes,
            List<DataEdgeRecord> dataEdges,
            List<EdgeEvidence> edgeEvidence,
            List<io.fieldtracegraph.core.model.CallEdgeRecord> callEdges,
            List<MethodSummaryRecord> methodSummaries,
            List<io.fieldtracegraph.core.model.PendingRefRecord> pendingRefs,
            Map<String, SourceFileRecord> sourceFiles,
            Map<String, String> scopeQnames
    ) {
    }

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
        options = ClasspathDiscovery.discover(root, options);
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
        long deadline = budget.deadlineNanos();
        long phaseStart = System.nanoTime();
        try (GraphReadSession session = snapshotReader.open(ProjectIndexPaths.databasePath(root))) {
            session.setDeadlineNanos(deadline);
            phaseStart = profile("open", phaseStart);
            var method = session.findMethodBySelector(methodSelector);
            if (method == null) {
                throw new IllegalArgumentException("METHOD_NOT_FOUND");
            }
            phaseStart = profile("method", phaseStart);
            Set<String> scopeIds = collectScopes(
                    session,
                    method.methodId(),
                    resolvedCallScope,
                    direction
            );
            phaseStart = profile("scopes=" + scopeIds.size(), phaseStart);
            ensureWithinDeadline(budget, deadline, "scope collection");
            List<String> scopeIdList = new ArrayList<>(scopeIds);
            String coneKey = session.generation() + "|" + strictContracts + "|"
                    + String.join("\u0001", scopeIdList);
            ConeSnapshot cone;
            synchronized (coneCache) {
                cone = coneCache.get(coneKey);
            }
            long coneStart = System.nanoTime();
            if (cone == null) {
                cone = loadCone(session, root, options, scopeIdList, strictContracts, budget, deadline);
                synchronized (coneCache) {
                    coneCache.put(coneKey, cone);
                }
            }
            phaseStart = profile("cone(cached=" + (System.nanoTime() - coneStart < 5_000_000L) + ")", phaseStart);
            List<ValueNodeRecord> valueNodes = cone.valueNodes();
            List<DataEdgeRecord> dataEdges = cone.dataEdges();
            List<EdgeEvidence> edgeEvidence = cone.edgeEvidence();
            List<CallEdgeRecord> callEdges = cone.callEdges();
            List<MethodSummaryRecord> methodSummaries = cone.methodSummaries();
            List<PendingRefRecord> pendingRefs = cone.pendingRefs();
            Map<String, SourceFileRecord> sourceFiles = cone.sourceFiles();
            Map<String, String> scopeQnames = cone.scopeQnames();
            List<SourceFileRecord> scannedFiles = captureSources(root, options);
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
                    scopeQnames,
                    includeDependencies
            );
            String freshness = computeFreshness(session, scannedFiles, root, options);
            phaseStart = profile("sources", phaseStart);
            List<AmbiguousCandidate> ambiguousCandidates = ambiguousCandidates(pendingRefs, scopeQnames);
            List<TraceWeakHop> weakHops = weakHops(pendingRefs, scopeQnames, sourceFiles);
            var traceResult = tracer.trace(
                    graph,
                    targetExpression,
                    query.direction(),
                    budget,
                    freshness,
                    ambiguousCandidates,
                    weakHops
            );
            profile("traverse=" + traceResult.edges().size(), phaseStart);
            return traceResult;
        } catch (RuntimeException e) {
            if (budgetInterrupted(e)) {
                throw new io.fieldtracegraph.core.query.TraceTimeoutException("storage scan");
            }
            throw e;
        }
    }

    // 作用：判断异常是否由预算中断引起；逻辑：沿 cause 链匹配 SQLite 的 interrupt 文案。
    private boolean budgetInterrupted(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("interrupt")) {
                return true;
            }
        }
        return false;
    }

    /*
     * Loads every row a single trace needs for its call cone. The returned
     * snapshot is immutable so it can be reused by the next field queried on
     * the same method without risking cross-case mutation.
     */
    private ConeSnapshot loadCone(
            GraphReadSession session,
            Path root,
            SourceScanOptions options,
            List<String> scopeIdList,
            boolean strictContracts,
            io.fieldtracegraph.core.query.TraversalBudget budget,
            long deadline
    ) {
        long phaseStart = System.nanoTime();
        List<ValueNodeRecord> valueNodes = session.valueNodesForScopeIds(scopeIdList);
        phaseStart = profile("nodes=" + valueNodes.size(), phaseStart);
        ensureWithinDeadline(budget, deadline, "value node loading");
        /*
         * Keep dependency edges in the graph as field evidence even when they
         * are not part of the requested value-flow projection. The index uses
         * them to build field expansions, while traversal only registers them
         * as steps when --include-dependencies is set.
         */
        List<DataEdgeRecord> dataEdges = session.dataEdgesForScopeIds(scopeIdList);
        phaseStart = profile("edges=" + dataEdges.size(), phaseStart);
        ensureWithinDeadline(budget, deadline, "data edge loading");
        /*
         * Load evidence only for the edges that are actually in memory. A
         * scope-wide evidence scan of a large repository materialises every
         * evidence row for every method in the call cone, which both wastes
         * memory and dominates query time for wide call graphs.
         */
        List<String> evidenceIds = dataEdges.stream()
                .map(DataEdgeRecord::evidenceId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        List<String> edgeIds = dataEdges.stream()
                .map(DataEdgeRecord::edgeId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        List<EdgeEvidence> edgeEvidence = new ArrayList<>(
                session.edgeEvidenceForEdgeRefs(evidenceIds, edgeIds)
        );
        phaseStart = profile("evidence=" + edgeEvidence.size(), phaseStart);
        ensureWithinDeadline(budget, deadline, "evidence loading");
        ProjectContracts contracts = contractLoader.load(root, strictContracts);
        edgeEvidence.addAll(manualContractEvidence.apply(
                contracts, dataEdges, valueNodes, strictContracts));
        List<CallEdgeRecord> callEdges = session.callEdgesForScopeIds(scopeIdList);
        List<MethodSummaryRecord> methodSummaries = session.methodSummariesForScopeIds(scopeIdList);
        List<PendingRefRecord> pendingRefs = session.pendingRefsForScopeIds(scopeIdList);
        phaseStart = profile("callgraph", phaseStart);
        ensureWithinDeadline(budget, deadline, "call graph loading");
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
        List<SourceFileRecord> scannedFiles = captureSources(root, options);
        Map<String, SourceFileRecord> sourceFiles = withSourceContent(
                session.sourceFilesByIds(new ArrayList<>(fileIds)),
                scannedFiles
        );
        Map<String, String> scopeQnames = session.qnamesBySymbolIds(scopeIdList);
        return new ConeSnapshot(
                List.copyOf(valueNodes),
                List.copyOf(dataEdges),
                List.copyOf(edgeEvidence),
                List.copyOf(callEdges),
                List.copyOf(methodSummaries),
                List.copyOf(pendingRefs),
                Map.copyOf(sourceFiles),
                Map.copyOf(scopeQnames)
        );
    }

    // 作用：输出阶段耗时；逻辑：仅在设置 FTG_TRACE_PROFILE 时打印，避免影响正常输出。
    private long profile(String phase, long startNanos) {
        long now = System.nanoTime();
        if (System.getenv("FTG_TRACE_PROFILE") != null) {
            System.err.printf("[profile] %-22s %6.0fms%n", phase, (now - startNanos) / 1_000_000d);
        }
        return now;
    }

    /**
     * Freshness verification needs the current file hashes. Re-reading and
     * re-hashing every source file for each case dominated batch runs on large
     * repositories. A read-only audit cannot outrun its own index, so a short
     * process-local cache turns that per-case cost into a one-off while still
     * refreshing periodically for long runs.
     */
    // 作用：读取源码快照；逻辑：同根目录同选项在 TTL 内复用，避免每条用例重复全仓读取与哈希。
    private List<SourceFileRecord> captureSources(Path root, SourceScanOptions options) {
        long now = System.currentTimeMillis();
        Path normalized = root.toAbsolutePath().normalize();
        synchronized (this) {
            if (capturedFiles != null
                    && normalized.equals(capturedRoot)
                    && options.equals(capturedOptions)
                    && now - capturedAtMillis <= SOURCE_SCAN_TTL_MILLIS) {
                return capturedFiles;
            }
            List<SourceFileRecord> files = sourceReader.capture(root, options);
            capturedRoot = normalized;
            capturedOptions = options;
            capturedFiles = files;
            capturedAtMillis = now;
            return files;
        }
    }

    // 作用：在加载阶段检查预算；逻辑：超时抛出专用异常，避免大仓库在物化证据时长时间无响应。
    private void ensureWithinDeadline(
            io.fieldtracegraph.core.query.TraversalBudget budget,
            long deadline,
            String phase
    ) {
        if (budget.expired(deadline)) {
            throw new io.fieldtracegraph.core.query.TraceTimeoutException(phase);
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
            if ("FIELD_ACCESSOR".equals(ref.referenceKind())) {
                /*
                 * An unverified external accessor is not a cross-call hop.
                 * Its field node reports the CLASSPATH_MISSING boundary where
                 * it matters, but counting every accessor in the call cone as
                 * a weak hop would downgrade otherwise complete traces.
                 */
                continue;
            }
            if (!ref.externalBoundary() || isExpandedFrameworkAdapter(ref)) {
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

    /**
     * Collection and tuple factories are expanded into field/element edges by
     * the Java extractor. They have no callee body, but they are no longer
     * unbridged value-flow boundaries, so counting them as weak hops would
     * make an exact trace look partial.
     */
    // 作用：识别已由提取器展开的框架工厂；逻辑：按工厂类型与方法名匹配集合、元组和 Map.entry。
    private boolean isExpandedFrameworkAdapter(PendingRefRecord ref) {
        String name = ref.referenceName();
        if (name == null) {
            return false;
        }
        if (name.contains(".stream()") || name.contains(".parallelStream()")) {
            /*
             * Stream pipelines are expanded by StreamFlowExtractor. The
             * terminal call has no callee body of its own, so it must not be
             * counted as a missing external bridge.
             */
            return true;
        }
        int separator = name.indexOf('#');
        if (separator <= 0) {
            return false;
        }
        String owner = name.substring(0, separator);
        int dot = owner.lastIndexOf('.');
        if (dot >= 0) {
            owner = owner.substring(dot + 1);
        }
        int arguments = name.indexOf('(', separator);
        String method = arguments < 0
                ? name.substring(separator + 1)
                : name.substring(separator + 1, arguments);
        if ("<init>".equals(method)) {
            return EXPANDED_CONSTRUCTOR_TYPES.contains(owner);
        }
        if (EXPANDED_WRAPPER_TYPES.contains(owner)
                && EXPANDED_WRAPPER_METHODS.contains(method)) {
            return true;
        }
        if (EXPANDED_UTILITY_TYPES.contains(owner)) {
            return true;
        }
        String normalizedOwner = owner.toLowerCase(java.util.Locale.ROOT);
        if ("copy".equals(method)
                && (normalizedOwner.contains("copier")
                || normalizedOwner.contains("beancopy"))) {
            /*
             * cglib BeanCopier is reached through a local variable named
             * {@code copier}; BeanCopyExtractor already emitted COPY_ALL and
             * concrete property edges for that call, so it is not an
             * unbridged value-flow boundary.
             */
            return true;
        }
        return EXPANDED_FACTORY_TYPES.contains(owner)
                && EXPANDED_FACTORY_METHODS.contains(method);
    }

    // 作用：由文件 ID 取路径；逻辑：查不到记录时返回 null。
    private String sourcePath(Map<String, SourceFileRecord> sourceFiles, String fileId) {
        SourceFileRecord file = sourceFiles.get(fileId);
        return file == null ? null : file.relativePath();
    }

}
