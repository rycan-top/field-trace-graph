package io.fieldtracegraph.core.query;

import io.fieldtracegraph.core.model.AmbiguousCandidate;
import io.fieldtracegraph.core.model.EdgeEvidence;
import io.fieldtracegraph.core.model.Provenance;
import io.fieldtracegraph.core.model.Resolution;
import io.fieldtracegraph.core.model.SourceEvidence;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.TraceBoundary;
import io.fieldtracegraph.core.model.TraceEdge;
import io.fieldtracegraph.core.model.TraceFileEvidence;
import io.fieldtracegraph.core.model.TraceLeaf;
import io.fieldtracegraph.core.model.TraceEvidenceIssue;
import io.fieldtracegraph.core.model.TraceNoMatchReason;
import io.fieldtracegraph.core.model.TraceNode;
import io.fieldtracegraph.core.model.TraceResult;
import io.fieldtracegraph.core.model.TraceWeakHop;
import io.fieldtracegraph.core.model.ValueNodeRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// 作用：合并正向与反向遍历的结果并去重。
// 使用场景：trace --direction both 时生成统一结果。

/**
 * Merges the shared traversal state into the final trace result while keeping
 * forward and backward direction, boundaries and evidence consistent.
 */
public final class LineageResultMerger {
    // 作用：构造未命中结果；逻辑：填充目标、方向与未命中原因，返回空图。
    TraceResult noMatch(
            LineageIndex index,
            String targetExpression,
            String direction,
            String freshness,
            String code,
            String message
    ) {
        return new TraceResult(
                index.method().methodId(),
                index.method().methodQname(),
                null,
                targetExpression,
                direction,
                index.generation(),
                index.graphStatus(),
                freshness,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true,
                new TraceNoMatchReason(code, message),
                List.of(),
                false,
                List.of(),
                false
        );
    }

    // 作用：合并正反向遍历结果；逻辑：合并节点与边、去重、筛选相关项并组装最终结果。
    public TraceResult merge(
            LineageIndex index,
            TraversalState state,
            ValueRef target,
            String targetExpression,
            String direction,
            String freshness,
            List<AmbiguousCandidate> ambiguousCandidates,
            List<TraceWeakHop> weakHops,
            boolean includeIn,
            boolean includeOut
    ) {
        Set<ValueRef> usefulRefs = usefulRefs(index, state.visited, state.backwardVisited, target);
        Set<String> usefulKeys = new HashSet<>();
        for (ValueRef ref : usefulRefs) {
            // Traversal contexts distinguish shared callees internally, while
            // TraceEdge identities deliberately use the context-free value key.
            usefulKeys.add(ValueRef.withoutCallContext(ref.key()));
        }
        List<TraceEdge> usefulEdges = state.edges.stream()
                .filter(edge -> usefulKeys.contains(edge.srcId()) && usefulKeys.contains(edge.dstId()))
                .toList();
        usefulEdges = dedupeEdges(pruneSymmetricBackflow(usefulEdges, target));
        BoundaryProjection boundaryProjection = boundaries(index, usefulRefs, includeIn, includeOut);
        List<SourceEvidence> sourceEvidence = evidence(index, usefulEdges);
        usefulEdges = operations(usefulEdges, sourceEvidence);
        LineageChainAssembler.ChainProjection chainProjection = chains(usefulEdges);
        if (chainProjection.countLimitReached()) {
            state.markTruncated("CHAIN_COUNT_LIMIT");
        }
        return new TraceResult(
                index.method().methodId(),
                index.method().methodQname(),
                target.nodeId(),
                targetExpression,
                direction,
                index.generation(),
                index.graphStatus(),
                freshness,
                nodes(index, usefulRefs),
                usefulEdges,
                chainProjection.chains(),
                boundaryProjection.boundaries(),
                boundaryProjection.leaves(),
                relevantAmbiguities(index, usefulRefs, usefulEdges, ambiguousCandidates),
                relevantWeakHops(index, usefulRefs, usefulEdges, weakHops),
                sourceEvidence,
                fileIndexes(index, usefulEdges),
                snapshotConsistent(index, usefulEdges),
                noMatchReason(index, usefulEdges),
                evidenceIssues(index, usefulEdges, freshness),
                state.truncated,
                List.copyOf(state.truncationReasons),
                chainProjection.cycleDetected()
        );
    }

    /**
     * A branch merge can be represented in both directions, for example
     * {@code phi -> value} and the reverse {@code value -> phi}. Keeping both
     * makes a normal field look cyclic. Remove the reverse PHI projection for
     * every branch merge, then collapse a reverse pair incident to the target.
     */
    // 作用：裁剪对称回流边；逻辑：去掉与目标方向相反且不携带新增信息的边。
    private List<TraceEdge> pruneSymmetricBackflow(List<TraceEdge> edges, ValueRef target) {
        String targetKey = target.key();
        Set<String> removedEdgeIds = new HashSet<>();
        Map<String, TraceEdge> phiByEndpoints = new HashMap<>();
        for (TraceEdge edge : edges) {
            if ("PHI_INPUT".equals(edge.edgeType())) {
                phiByEndpoints.put(edgeKey(edge.srcId(), edge.dstId()), edge);
            }
        }
        for (TraceEdge edge : edges) {
            if ("ASSIGNED_FROM".equals(edge.edgeType())
                    && "branch merge".equalsIgnoreCase(edge.operation())) {
                TraceEdge reverse = phiByEndpoints.get(edgeKey(edge.dstId(), edge.srcId()));
                if (reverse != null) {
                    removedEdgeIds.add(reverse.edgeId());
                }
            }
        }
        Map<String, List<TraceEdge>> incident = new LinkedHashMap<>();
        for (TraceEdge edge : edges) {
            String other = targetKey.equals(edge.srcId())
                    ? edge.dstId()
                    : targetKey.equals(edge.dstId()) ? edge.srcId() : null;
            if (other != null) {
                incident.computeIfAbsent(other, ignored -> new ArrayList<>()).add(edge);
            }
        }
        for (List<TraceEdge> pair : incident.values()) {
            if (pair.size() != 2) {
                continue;
            }
            TraceEdge first = pair.get(0);
            TraceEdge second = pair.get(1);
            if (!first.srcId().equals(second.dstId()) || !first.dstId().equals(second.srcId())) {
                continue;
            }
            TraceEdge keep = preferredTargetEdge(first, second, targetKey);
            TraceEdge remove = keep == first ? second : first;
            removedEdgeIds.add(remove.edgeId());
        }
        return edges.stream()
                .filter(edge -> !removedEdgeIds.contains(edge.edgeId()))
                .toList();
    }

    // 作用：生成边去重键；逻辑：拼接源与目标节点标识。
    private String edgeKey(String srcId, String dstId) {
        return srcId + '\u0000' + dstId;
    }

    /**
     * One physical effect can be projected both at object level and at field
     * level. Keep one edge per endpoints/type/direction and prefer the bridged
     * projection because it carries call-site and callee-effect evidence.
     */
    // 作用：对结果边去重；逻辑：按边键分组并保留证据更强的代表。
    private List<TraceEdge> dedupeEdges(List<TraceEdge> edges) {
        Map<String, TraceEdge> selected = new LinkedHashMap<>();
        for (TraceEdge edge : edges) {
            String key = ValueRef.withoutCallContext(edge.srcId()) + "|"
                    + ValueRef.withoutCallContext(edge.dstId()) + "|"
                    + edge.edgeType() + "|" + edge.direction() + "|" + edge.evidenceId();
            selected.merge(key, edge, this::preferredEdge);
        }
        return List.copyOf(selected.values());
    }

    // 作用：选择更优的边；逻辑：依次比较解析强度、证据数量与来源权重。
    private TraceEdge preferredEdge(TraceEdge first, TraceEdge second) {
        int firstScore = evidenceScore(first);
        int secondScore = evidenceScore(second);
        TraceEdge selected = secondScore > firstScore ? second : first;
        TraceEdge other = selected == first ? second : first;
        if (other.evidence().isEmpty()) {
            return selected;
        }
        Map<String, EdgeEvidence> evidence = new LinkedHashMap<>();
        for (EdgeEvidence item : selected.evidence()) {
            evidence.put(item.evidenceId(), item);
        }
        for (EdgeEvidence item : other.evidence()) {
            evidence.putIfAbsent(item.evidenceId(), item);
        }
        List<EdgeEvidence> merged = List.copyOf(evidence.values());
        String resolution = Resolution.aggregate(merged.stream()
                .map(item -> Resolution.from(item.resolution()))
                .toList()).name();
        String provenance = Provenance.aggregate(merged.stream()
                .map(item -> Provenance.from(item.provenance()))
                .toList()).name();
        return selected.withEvidence(resolution, provenance, merged);
    }

    // 作用：计算边的证据得分；逻辑：按证据条数与来源权重累加。
    private int evidenceScore(TraceEdge edge) {
        int score = 0;
        if (edge.bridged()) {
            score += 4;
        }
        if (edge.effectLocation() != null && !edge.effectLocation().isBlank()) {
            score += 2;
        }
        if (edge.operation() != null && !edge.operation().isBlank()) {
            score++;
        }
        score += Math.min(edge.evidence().size(), 3);
        return score;
    }

    // 作用：选择与目标最相关的边；逻辑：优先目标键匹配且证据更强的边。
    private TraceEdge preferredTargetEdge(TraceEdge first, TraceEdge second, String targetKey) {
        return edgePriority(first, targetKey) >= edgePriority(second, targetKey)
                ? first
                : second;
    }

    // 作用：计算边相对目标的优先级；逻辑：由目标键命中、方向与证据共同决定。
    private int edgePriority(TraceEdge edge, String targetKey) {
        if ("IN".equals(edge.direction()) && targetKey.equals(edge.dstId())) {
            return 3;
        }
        if ("IN".equals(edge.direction())) {
            return 2;
        }
        return targetKey.equals(edge.srcId()) ? 0 : 1;
    }

    /**
     * Keeps only values that lie on a path to the selected target or to a
     * field that has real lineage evidence. Object parameters that merely pass
     * an unused field through unrelated methods are removed from the visible
     * result instead of being rendered as repeated dead-end nodes.
     */
    // 作用：筛选与结果相关的引用；逻辑：从目标出发收集可达引用并去重。
    private Set<ValueRef> usefulRefs(
            LineageIndex index,
            Set<ValueRef> visited,
            Set<ValueRef> backwardVisited,
            ValueRef target
    ) {
        Set<ValueRef> anchors = new HashSet<>();
        for (ValueRef ref : visited) {
            ValueNodeRecord node = index.node(ref.nodeId());
            if (node == null) {
                continue;
            }
            if (ref.equals(target)
                    || (node.accessPath() != null && !node.accessPath().isEmpty() && ref.fields().isEmpty())
                    || (!ref.fields().isEmpty()
                    && index.hasFieldNode(node.scopeSymbolId(), node.canonicalValue(), ref.fields()))) {
                anchors.add(ref);
            }
        }
        Map<ValueRef, List<ValueRef>> outgoing = new HashMap<>();
        for (ValueRef ref : visited) {
            outgoing.put(ref, index.outgoing(ref).stream()
                    .map(Step::target)
                    .filter(visited::contains)
                    .toList());
        }
        Set<ValueRef> backwardUseful = new LinkedHashSet<>();
        Map<ValueRef, Boolean> memo = new HashMap<>();
        for (ValueRef ref : visited) {
            if (anchors.contains(ref) || reachesAnchor(ref, anchors, outgoing, memo, new HashSet<>())) {
                backwardUseful.add(ref);
            }
        }
        Set<ValueRef> useful = new LinkedHashSet<>(backwardVisited);
        useful.addAll(backwardUseful);
        /*
         * A field target is an SSA version, not the end of its use chain. Keep
         * nodes reachable from that version as well, otherwise a later
         * stream/get call is visible in the graph but disappears from the
         * rendered trace. This is completed only for refs already connected to
         * the selected target, so unrelated object parameters stay filtered.
         */
        for (ValueRef ref : backwardUseful) {
            collectReachable(ref, outgoing, useful, new HashSet<>());
        }
        return useful;
    }

    // 作用：收集可达引用；逻辑：沿结果边遍历并写入集合。
    private void collectReachable(
            ValueRef ref,
            Map<ValueRef, List<ValueRef>> outgoing,
            Set<ValueRef> useful,
            Set<ValueRef> visiting
    ) {
        if (!visiting.add(ref)) {
            return;
        }
        for (ValueRef next : outgoing.getOrDefault(ref, List.of())) {
            if (useful.add(next)) {
                collectReachable(next, outgoing, useful, visiting);
            }
        }
        visiting.remove(ref);
    }

    // 作用：判断引用能否到达锚点；逻辑：沿边递归搜索目标节点。
    private boolean reachesAnchor(
            ValueRef ref,
            Set<ValueRef> anchors,
            Map<ValueRef, List<ValueRef>> outgoing,
            Map<ValueRef, Boolean> memo,
            Set<ValueRef> visiting
    ) {
        if (anchors.contains(ref)) {
            return true;
        }
        Boolean cached = memo.get(ref);
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(ref)) {
            return false;
        }
        boolean result = outgoing.getOrDefault(ref, List.of()).stream()
                .anyMatch(next -> reachesAnchor(next, anchors, outgoing, memo, visiting));
        visiting.remove(ref);
        memo.put(ref, result);
        return result;
    }

    // 作用：筛选相关歧义候选；逻辑：只保留出现在可达引用中的候选。
    private List<AmbiguousCandidate> relevantAmbiguities(
            LineageIndex index,
            Set<ValueRef> usefulRefs,
            List<TraceEdge> usefulEdges,
            List<AmbiguousCandidate> candidates
    ) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> visitedScopes = new java.util.LinkedHashSet<>();
        java.util.Set<String> evidenceLocations = new java.util.LinkedHashSet<>();
        for (ValueRef ref : usefulRefs) {
            ValueNodeRecord node = index.node(ref.nodeId());
            if (node != null) {
                visitedScopes.add(node.scopeSymbolId());
            }
        }
        for (var edge : usefulEdges) {
            Step step = index.stepByEvidenceId(edge.evidenceId());
            if (step != null) {
                evidenceLocations.add(step.fileId() + ":" + step.lineStart());
            }
        }
        visitedScopes.add(index.method().methodId());
        return candidates.stream()
                .filter(candidate -> candidate.callerSymbolId() == null
                        || visitedScopes.contains(candidate.callerSymbolId()))
                .filter(candidate -> evidenceLocations.isEmpty()
                        || evidenceLocations.contains(candidate.fileId() + ":" + candidate.lineStart()))
                .toList();
    }

    // 作用：筛选相关弱跳；逻辑：只保留落在结果边上的弱跳。
    private List<TraceWeakHop> relevantWeakHops(
            LineageIndex index,
            Set<ValueRef> usefulRefs,
            List<TraceEdge> usefulEdges,
            List<TraceWeakHop> weakHops
    ) {
        if (weakHops.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> visitedScopes = new java.util.LinkedHashSet<>();
        java.util.Set<String> usefulNodeIds = new java.util.LinkedHashSet<>();
        java.util.Set<String> evidenceLocations = new java.util.LinkedHashSet<>();
        for (ValueRef ref : usefulRefs) {
            ValueNodeRecord node = index.node(ref.nodeId());
            if (node != null) {
                visitedScopes.add(node.scopeSymbolId());
                usefulNodeIds.add(node.valueId());
            }
        }
        for (var edge : usefulEdges) {
            Step step = index.stepByEvidenceId(edge.evidenceId());
            if (step != null) {
                evidenceLocations.add(step.fileId() + ":" + step.lineStart());
            }
        }
        visitedScopes.add(index.method().methodId());
        return weakHops.stream()
                .filter(hop -> hop.callerSymbolId() == null || visitedScopes.contains(hop.callerSymbolId()))
                .filter(hop -> evidenceLocations.isEmpty()
                        || evidenceLocations.contains(hop.fileId() + ":" + hop.lineStart()))
                .filter(hop -> touchesTracedValue(hop, usefulNodeIds))
                .toList();
    }

    // 作用：判断弱跳是否接触被追踪值；逻辑：比较接收者、实参与结果节点，无值引用时保留方法级证据。
    private boolean touchesTracedValue(TraceWeakHop hop, Set<String> usefulNodeIds) {
        boolean hasValueReference = hop.receiverValueId() != null
                || hop.resultValueId() != null
                || (hop.argumentValueIds() != null && !hop.argumentValueIds().isEmpty());
        if (!hasValueReference) {
            return true;
        }
        if (hop.receiverValueId() != null && usefulNodeIds.contains(hop.receiverValueId())) {
            return true;
        }
        if (hop.resultValueId() != null && usefulNodeIds.contains(hop.resultValueId())) {
            return true;
        }
        if (hop.argumentValueIds() != null) {
            for (String argumentValueId : hop.argumentValueIds()) {
                if (argumentValueId != null && usefulNodeIds.contains(argumentValueId)) {
                    return true;
                }
            }
        }
        return false;
    }

    // 作用：把访问引用转为输出节点；逻辑：逐个调用索引转换并保持顺序。
    private List<TraceNode> nodes(LineageIndex index, Set<ValueRef> visited) {
        Map<String, TraceNode> unique = new LinkedHashMap<>();
        for (ValueRef ref : visited) {
            TraceNode node = index.toTraceNode(new ValueRef(ref.nodeId(), ref.fields()));
            unique.putIfAbsent(node.nodeId(), node);
        }
        return List.copyOf(unique.values());
    }

    // 作用：投影开放边界；逻辑：按方向收集边界证据并生成边界列表。
    private BoundaryProjection boundaries(LineageIndex index, Set<ValueRef> visited, boolean includeIn, boolean includeOut) {
        Map<String, TraceBoundary> boundaries = new LinkedHashMap<>();
        Map<String, TraceLeaf> leaves = new LinkedHashMap<>();
        for (ValueRef ref : visited) {
            ValueNodeRecord node = index.node(ref.nodeId());
            if (node == null) {
                continue;
            }
            if (includeIn && index.incoming(ref).isEmpty()) {
                String reason = boundaryReason(index, ref, true);
                String key = ValueRef.withoutCallContext(ref.key());
                TraceBoundary boundary = boundary(index, ref, key, "IN", reason, false);
                boundaries.putIfAbsent(key + "|IN|" + reason, boundary);
                TraceLeaf leaf = leaf(index, ref, "IN", reason);
                leaves.putIfAbsent(key + "|IN|" + reason, leaf);
            }
            if (includeOut && index.outgoing(ref).isEmpty()) {
                if ("CALL_RESULT".equals(node.valueKind())
                        && ref.fields().isEmpty()
                        && index.isVoidCallResult(node.valueId())) {
                    // A void call has no result value; its bookkeeping node is
                    // not an unused field.
                    continue;
                }
                if (!ref.fields().isEmpty()
                        && "PARAMETER".equals(node.valueKind())
                        && !index.hasFieldNode(node.scopeSymbolId(), node.canonicalValue(), ref.fields())) {
                    // The object was passed through this method, but the
                    // method never accessed this field. It is not a lineage
                    // boundary of the field, only an unused parameter.
                    continue;
                }
                String reason = boundaryReason(index, ref, false);
                String key = ValueRef.withoutCallContext(ref.key());
                TraceBoundary boundary = boundary(index, ref, key, "OUT", reason, true);
                boundaries.putIfAbsent(key + "|OUT|" + reason, boundary);
                TraceLeaf leaf = leaf(index, ref, "OUT", reason);
                leaves.putIfAbsent(key + "|OUT|" + reason, leaf);
            }
        }
        return new BoundaryProjection(
                List.copyOf(boundaries.values()),
                List.copyOf(leaves.values())
        );
    }

    // 作用：构造单条开放边界；逻辑：填充原因、可恢复性、建议动作与证据位置。
    private TraceBoundary boundary(
            LineageIndex index,
            ValueRef ref,
            String key,
            String direction,
            String reason,
            boolean useIncomingEvidence
    ) {
        TraceNode node = index.toTraceNode(new ValueRef(ref.nodeId(), ref.fields()));
        Step evidence = boundaryEvidence(index, ref, useIncomingEvidence);
        BoundaryLocation location = boundaryLocation(index, ref, evidence);
        BoundaryGuidance guidance = boundaryGuidance(reason, direction, evidence);
        return new TraceBoundary(
                key,
                node.scopeId(),
                direction,
                reason,
                evidence == null ? null : evidence.evidenceId(),
                node.displayName(),
                node.kind(),
                location.sourceFile(),
                location.lineStart(),
                location.columnStart(),
                location.lineEnd(),
                location.columnEnd(),
                guidance.reasonCode(),
                guidance.recoverable(),
                guidance.suggestedAction(),
                guidance.evidenceSource()
        );
    }

    // 作用：生成边界处置建议；逻辑：按原因码与方向映射建议动作和说明。
    private BoundaryGuidance boundaryGuidance(String reason, String direction, Step evidence) {
        String evidenceSource = evidence == null
                ? Provenance.SOURCE.name()
                : evidence.provenance();
        return switch (reason) {
            case "RPC_BOUNDARY" -> new BoundaryGuidance(
                    "RPC_BOUNDARY",
                    true,
                    "import-runtime-trace-or-provide-contract",
                    Provenance.FRAMEWORK_ADAPTER.name()
            );
            case "SERIALIZATION_BOUNDARY" -> new BoundaryGuidance(
                    "SERIALIZATION_BOUNDARY",
                    true,
                    "import-runtime-trace-or-provide-contract",
                    Provenance.FRAMEWORK_ADAPTER.name()
            );
            case "RPC_SERIALIZATION" -> new BoundaryGuidance(
                    "RPC_SERIALIZATION",
                    true,
                    "import-runtime-trace-or-provide-contract",
                    Provenance.FRAMEWORK_ADAPTER.name()
            );
            case "EXTERNAL_LIBRARY", "EXTERNAL", "EXTERNAL_BOUNDARY" -> new BoundaryGuidance(
                    "EXTERNAL_LIBRARY",
                    true,
                    "provide-classpath-or-runtime-trace",
                    evidenceSource
            );
            case "PLATFORM_LIBRARY" -> new BoundaryGuidance(
                    "PLATFORM_LIBRARY",
                    false,
                    "none",
                    Provenance.BYTECODE.name()
            );
            case "CLASSPATH_MISSING" -> new BoundaryGuidance(
                    "CLASSPATH_MISSING",
                    true,
                    "provide-classpath",
                    evidenceSource
            );
            case "CALLEE_SYMBOL_MISSING" -> new BoundaryGuidance(
                    "CALLEE_SYMBOL_MISSING",
                    true,
                    "reindex-with-generated-sources-or-provide-classpath",
                    evidenceSource
            );
            case "GENERATED_CODE_MISSING" -> new BoundaryGuidance(
                    "GENERATED_CODE_MISSING",
                    true,
                    "index-generated-sources-or-provide-classpath",
                    Provenance.GENERATED_SOURCE.name()
            );
            case "RECEIVER_TYPE_UNKNOWN" -> new BoundaryGuidance(
                    "RECEIVER_TYPE_UNKNOWN",
                    true,
                    "trace-receiver-expression-or-import-runtime-trace",
                    evidenceSource
            );
            case "OVERLOAD_AMBIGUOUS", "AMBIGUOUS_OVERLOAD" -> new BoundaryGuidance(
                    "OVERLOAD_AMBIGUOUS",
                    true,
                    "disambiguate-overload-or-provide-classpath",
                    evidenceSource
            );
            case "DYNAMIC_PROXY", "AOP" -> new BoundaryGuidance(
                    reason,
                    true,
                    "import-runtime-trace",
                    Provenance.FRAMEWORK_ADAPTER.name()
            );
            case "REFLECTION" -> new BoundaryGuidance(
                    "REFLECTION",
                    true,
                    "import-runtime-trace",
                    evidenceSource
            );
            case "PARAMETER" -> new BoundaryGuidance(
                    "PARAMETER",
                    true,
                    "trace-caller-or-provide-input",
                    evidenceSource
            );
            case "CONSTANT" -> new BoundaryGuidance(
                    "CONSTANT",
                    false,
                    "none",
                    evidenceSource
            );
            case "RETURN" -> new BoundaryGuidance(
                    "RETURN",
                    false,
                    "none",
                    evidenceSource
            );
            case "FIELD" -> "IN".equals(direction)
                    ? new BoundaryGuidance(
                            "FIELD_STATE",
                            true,
                            "import-runtime-trace",
                            evidenceSource
                    )
                    : new BoundaryGuidance(
                            "NO_DOWNSTREAM_USE",
                            false,
                            "none",
                            evidenceSource
                    );
            case "SQL_PARAMETER" -> new BoundaryGuidance(
                    "DYNAMIC_SQL",
                    true,
                    "import-runtime-sql",
                    Provenance.FRAMEWORK_ADAPTER.name()
            );
            case "NO_SOURCE" -> new BoundaryGuidance(
                    "UNKNOWN_SOURCE",
                    true,
                    "provide-generated-sources-or-classpath",
                    evidenceSource
            );
            case "VIRTUAL_AMBIGUOUS" -> new BoundaryGuidance(
                    "VIRTUAL_AMBIGUOUS",
                    true,
                    "provide-classpath-or-runtime-trace",
                    evidenceSource
            );
            case "USE_UNRESOLVED" -> new BoundaryGuidance(
                    "USE_UNRESOLVED",
                    true,
                    "resolve-call-target-or-provide-classpath",
                    evidenceSource
            );
            case "CONSUMED_BY_CALL" -> new BoundaryGuidance(
                    "CONSUMED_BY_CALL",
                    false,
                    "none",
                    evidenceSource
            );
            case "NO_CONSUMER", "NO_USE" -> new BoundaryGuidance(
                    "NO_CONSUMER",
                    false,
                    "none",
                    evidenceSource
            );
            default -> new BoundaryGuidance(
                    reason,
                    true,
                    "inspect-boundary-evidence",
                    evidenceSource
            );
        };
    }

    // 作用：构造链路端点；逻辑：写入引用、方向与原因。
    private TraceLeaf leaf(LineageIndex index, ValueRef ref, String direction, String reason) {
        TraceNode node = index.toTraceNode(new ValueRef(ref.nodeId(), ref.fields()));
        Step evidence = boundaryEvidence(index, ref, "OUT".equals(direction));
        BoundaryLocation location = boundaryLocation(index, ref, evidence);
        return new TraceLeaf(
                ValueRef.withoutCallContext(ref.key()),
                node.displayName(),
                node.kind(),
                node.scopeId(),
                direction,
                reason,
                evidence == null ? null : evidence.evidenceId(),
                location.sourceFile(),
                location.lineStart(),
                location.columnStart(),
                location.lineEnd(),
                location.columnEnd()
        );
    }

    /**
     * IN boundaries have no producer, so their first consumer step is the
     * closest evidence. OUT boundaries keep the last producer step. This keeps
     * a boundary actionable without pretending it has a lineage edge.
     */
    // 作用：取边界对应的证据步；逻辑：按方向选择入边或出边证据。
    private Step boundaryEvidence(LineageIndex index, ValueRef ref, boolean useIncomingEvidence) {
        List<Step> steps = useIncomingEvidence ? index.incoming(ref) : index.outgoing(ref);
        return steps.isEmpty() ? null : steps.get(0);
    }

    // 作用：定位边界所在文件与行号；逻辑：从证据步读取位置信息。
    private BoundaryLocation boundaryLocation(LineageIndex index, ValueRef ref, Step evidence) {
        ValueNodeRecord node = index.node(ref.nodeId());
        if (node != null
                && node.originFile() != null
                && !node.originFile().isBlank()
                && node.originLineStart() > 0) {
            return new BoundaryLocation(
                    node.originFile(),
                    node.originLineStart(),
                    node.originColumnStart(),
                    node.originLineEnd(),
                    node.originColumnEnd()
            );
        }
        if (evidence == null) {
            return BoundaryLocation.EMPTY;
        }
        SourceFileRecord file = index.file(evidence.fileId());
        return new BoundaryLocation(
                file == null ? null : file.relativePath(),
                evidence.lineStart(),
                evidence.columnStart(),
                evidence.lineEnd(),
                evidence.columnEnd()
        );
    }

    // 作用：组装链路投影；逻辑：委托链路组装器处理结果边。
    private LineageChainAssembler.ChainProjection chains(List<TraceEdge> edges) {
        return new LineageChainAssembler().project(edges);
    }

    // 作用：收集源码证据；逻辑：按证据 ID 去重并生成证据对象。
    private List<SourceEvidence> evidence(LineageIndex index, List<TraceEdge> edges) {
        Map<String, SourceEvidence> unique = new LinkedHashMap<>();
        for (TraceEdge edge : edges) {
            Step step = index.stepByEvidenceId(edge.evidenceId());
            if (step == null) {
                continue;
            }
            SourceFileRecord file = index.file(step.fileId());
            if (file != null) {
                unique.putIfAbsent(edge.evidenceId(), new SourceEvidence(
                        edge.evidenceId(),
                        file.relativePath(),
                        file.contentHash(),
                        step.lineStart(),
                        step.columnStart(),
                        step.lineEnd(),
                        step.columnEnd(),
                        sourceExpression(file, step),
                        step.effectLocation()
                ));
            }
        }
        return List.copyOf(unique.values());
    }

    /**
     * Old graph generations may have an empty operation even though their
     * source location is valid. Prefer the exact source expression, then use a
     * stable human-readable edge-type label so every rendered edge explains
     * what happened instead of exposing an empty column.
     */
    // 作用：为结果边补充操作描述；逻辑：按边类型生成可读操作标签。
    private List<TraceEdge> operations(List<TraceEdge> edges, List<SourceEvidence> evidence) {
        Map<String, String> expressions = new HashMap<>();
        for (SourceEvidence item : evidence) {
            if (item.evidenceId() != null && item.expression() != null && !item.expression().isBlank()) {
                expressions.putIfAbsent(item.evidenceId(), item.expression());
            }
        }
        List<TraceEdge> result = new ArrayList<>(edges.size());
        for (TraceEdge edge : edges) {
            if (edge.operation() != null && !edge.operation().isBlank()) {
                result.add(edge);
                continue;
            }
            String expression = expressions.get(edge.evidenceId());
            String operation = expression == null || expression.isBlank()
                    ? operationLabel(edge.edgeType())
                    : expression;
            result.add(edge.withOperation(operation));
        }
        return List.copyOf(result);
    }

    // 作用：生成边的操作标签；逻辑：把边类型映射为可读动作名。
    private String operationLabel(String edgeType) {
        if (edgeType == null || edgeType.isBlank()) {
            return "data flow";
        }
        return switch (edgeType) {
            case "ASSIGNED_FROM" -> "assignment";
            case "FIELD_READ" -> "field read";
            case "FIELD_WRITE" -> "field write";
            case "ARGUMENT_TO" -> "argument passed to callee parameter";
            case "RETURN_FROM" -> "return value from callee";
            case "CALL_PRODUCER" -> "external or unresolved call boundary";
            case "CONTAINER_ELEMENT" -> "container element";
            case "CONTAINER_FLOW" -> "container flow";
            case "MUTATES_PARAMETER" -> "callee mutates parameter";
            case "ADD_ELEMENT" -> "element added";
            case "WRITE_ELEMENT" -> "element written";
            case "PUT_ELEMENT" -> "map entry written";
            case "WRITE_ELEMENT_FIELD" -> "element field written";
            case "SETTER_WRITE" -> "setter write";
            case "COPIES_ELEMENTS" -> "copies container elements";
            case "WRITES_ELEMENT" -> "writes container element";
            case "PHI_INPUT" -> "control-flow merge";
            case "COPY_ALL" -> "copy";
            case "BUILDER_RESULT" -> "builder result";
            case "DERIVED_FROM" -> "derived value";
            default -> edgeType.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    /**
     * Preserves the exact source expression behind an edge. This is the
     * evidence counterpart to the graph contract's edge expression and lets users audit a
     * getter/setter call without promoting the call to a business field node.
     */
    // 作用：截取源码表达式片段；逻辑：按行列范围从文件内容读取。
    private String sourceExpression(SourceFileRecord file, Step step) {
        if (file == null || file.content() == null || file.content().isEmpty()
                || step.lineStart() <= 0 || step.lineEnd() < step.lineStart()) {
            return null;
        }
        String[] lines = file.content().split("\\R", -1);
        if (step.lineStart() > lines.length) {
            return null;
        }
        int start = step.lineStart() - 1;
        int end = Math.min(lines.length - 1, step.lineEnd() - 1);
        StringBuilder expression = new StringBuilder();
        for (int index = start; index <= end; index++) {
            String line = lines[index];
            int from = index == start && step.columnStart() > 0
                    ? Math.min(line.length(), step.columnStart() - 1) : 0;
            int to = index == end && step.columnEnd() > 0
                    ? Math.min(line.length(), Math.max(from, step.columnEnd())) : line.length();
            if (from < to) {
                if (!expression.isEmpty()) {
                    expression.append(' ');
                }
                expression.append(line, from, to);
            }
        }
        String normalized = expression.toString().replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    /**
     * Groups evidence by the physical file that produced it. This is the CLI
     * equivalent of the graph contract's {@code fileIndexes}, but keeps the snapshot hash so a
     * consumer can tell which bytes were analysed.
     */
    // 作用：汇总文件级证据索引；逻辑：按文件分组边并生成索引项。
    private List<TraceFileEvidence> fileIndexes(LineageIndex index, List<TraceEdge> edges) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (TraceEdge edge : edges) {
            Step step = index.stepByEvidenceId(edge.evidenceId());
            if (step != null) {
                counts.merge(step.fileId(), 1, Integer::sum);
            }
        }
        List<TraceFileEvidence> result = new ArrayList<>();
        for (var entry : counts.entrySet()) {
            SourceFileRecord file = index.file(entry.getKey());
            if (file != null) {
                result.add(new TraceFileEvidence(
                        entry.getKey(),
                        file.relativePath(),
                        file.contentHash(),
                        entry.getValue()
                ));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Every edge must resolve to a source record from the same read snapshot.
     * A missing record would mean a partially published graph and must be
     * visible to callers instead of being silently rendered.
     */
    // 作用：判断快照是否一致；逻辑：比较索引代数与当前快照并检查证据文件。
    private boolean snapshotConsistent(LineageIndex index, List<TraceEdge> edges) {
        for (TraceEdge edge : edges) {
            Step step = index.stepByEvidenceId(edge.evidenceId());
            if (step == null || index.file(step.fileId()) == null) {
                return false;
            }
        }
        return true;
    }

    // 作用：生成未命中原因；逻辑：根据边与索引状态推断具体原因。
    private TraceNoMatchReason noMatchReason(LineageIndex index, List<TraceEdge> edges) {
        if (!edges.isEmpty()) {
            return null;
        }
        if (index.valueNodes().isEmpty()) {
            return new TraceNoMatchReason(
                    "GRAPH_DATA_MISSING",
                    "The method index exists but no value graph was published"
            );
        }
        return new TraceNoMatchReason(
                "FIELD_HAS_NO_DATA_EDGES",
                "The target exists but has no traceable data edge in this method"
        );
    }

    // 作用：收集证据问题；逻辑：检查缺失或冲突的证据并生成诊断。
    private List<TraceEvidenceIssue> evidenceIssues(
            LineageIndex index,
            List<TraceEdge> edges,
            String freshness
    ) {
        List<TraceEvidenceIssue> issues = new ArrayList<>();
        if ("STALE".equals(freshness)) {
            issues.add(new TraceEvidenceIssue(
                    "INDEX_STALE",
                    "The published graph does not match the current source tree",
                    null,
                    0
            ));
        }
        for (TraceEdge edge : edges) {
            Step step = index.stepByEvidenceId(edge.evidenceId());
            if (step == null) {
                issues.add(new TraceEvidenceIssue(
                        "EVIDENCE_MISSING",
                        "The edge has no resolvable evidence step",
                        edge.sourceFile(),
                        edge.lineStart()
                ));
                continue;
            }
            SourceFileRecord file = index.file(step.fileId());
            if (file == null) {
                issues.add(new TraceEvidenceIssue(
                        "SOURCE_FILE_MISSING",
                        "The evidence file is absent from this index snapshot",
                        null,
                        step.lineStart()
                ));
            } else if (file.contentHash() == null || file.contentHash().isBlank()) {
                issues.add(new TraceEvidenceIssue(
                        "SOURCE_HASH_MISSING",
                        "The evidence file has no content hash",
                        file.relativePath(),
                        step.lineStart()
                ));
            }
        }
        return List.copyOf(issues);
    }

    // 作用：推断边界原因；逻辑：按方向的证据与外部原因码决定原因。
    private String boundaryReason(LineageIndex index, ValueRef ref, boolean incoming) {
        ValueNodeRecord node = index.node(ref.nodeId());
        if (node == null) {
            return "EXTERNAL";
        }
        String externalReason = index.externalReasonForValue(node.valueId());
        if (externalReason != null) {
            return externalReason;
        }
        if (incoming
                && "CALL_RESULT".equals(node.valueKind())
                && node.canonicalValue() != null
                && !node.canonicalValue().startsWith("new:")) {
            return "EXTERNAL_BOUNDARY";
        }
        if (!incoming && index.hasUnresolvedConsumer(ref)) {
            /*
             * The graph contains a consumer call site but no body to bridge
             * into. This is a connectivity gap, not evidence that the field is
             * unused in business code.
             */
            return "USE_UNRESOLVED";
        }
        if (!incoming && index.hasCallConsumer(ref)) {
            /*
             * The value is passed to a call whose result does not expose this
             * field (typically a void method). The consumer exists, so the
             * correct terminal state is "consumed by a call", not "unused".
             */
            return "CONSUMED_BY_CALL";
        }
        if ("PARAMETER".equals(node.valueKind())) {
            return "PARAMETER";
        }
        if ("CONSTANT".equals(node.valueKind())) {
            return "CONSTANT";
        }
        if ("RETURN".equals(node.valueKind())) {
            return "RETURN";
        }
        if ("FIELD".equals(node.valueKind())) {
            return "FIELD";
        }
        if ("EXTERNAL_BOUNDARY".equals(node.valueKind())) {
            return "EXTERNAL_BOUNDARY";
        }
        if ("SQL_PARAMETER".equals(node.valueKind())) {
            return "SQL_PARAMETER";
        }
        return incoming ? "NO_SOURCE" : "NO_CONSUMER";
    }

    private record BoundaryProjection(List<TraceBoundary> boundaries, List<TraceLeaf> leaves) {
    }

    private record BoundaryGuidance(
            String reasonCode,
            boolean recoverable,
            String suggestedAction,
            String evidenceSource
    ) {
    }

    private record BoundaryLocation(
            String sourceFile,
            int lineStart,
            int columnStart,
            int lineEnd,
            int columnEnd
    ) {
        private static final BoundaryLocation EMPTY = new BoundaryLocation(null, 0, 0, 0, 0);
    }
}
