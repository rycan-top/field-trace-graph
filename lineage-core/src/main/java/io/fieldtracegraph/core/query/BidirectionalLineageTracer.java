package io.fieldtracegraph.core.query;

import io.fieldtracegraph.core.model.AmbiguousCandidate;
import io.fieldtracegraph.core.model.CallEdgeRecord;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.TraceResult;
import io.fieldtracegraph.core.model.TraceWeakHop;
import io.fieldtracegraph.core.model.ValueNodeRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：双向遍历调度与结果合并。
// 使用场景：trace --direction both 的默认执行路径。

/**
 * Schedules the forward and backward traversals under a shared budget and
 * delegates final assembly to {@link LineageResultMerger}. It owns traversal
 * orchestration only, not index construction or rendering.
 */
public final class BidirectionalLineageTracer {
    private static final Set<String> DIRECTIONS = Set.of("in", "out", "both");

    private final ForwardLineageTraversal forwardTraversal = new ForwardLineageTraversal();
    private final BackwardLineageTraversal backwardTraversal = new BackwardLineageTraversal();
    private final LineageResultMerger resultMerger = new LineageResultMerger();
    private final LambdaElementAliasResolver lambdaElementAliasResolver = new LambdaElementAliasResolver();

    // 作用：执行一次双向血缘追踪；逻辑：解析目标、按方向选择遍历策略、合并结果并计算质量结论。
    public TraceResult trace(
            LineageGraph graph,
            String targetExpression,
            String direction,
            TraversalBudget budget,
            String freshness,
            List<AmbiguousCandidate> ambiguousCandidates,
            List<TraceWeakHop> weakHops
    ) {
        if (direction == null || !DIRECTIONS.contains(direction)) {
            throw new IllegalArgumentException("direction must be one of: in, out, both");
        }
        long deadline = budget.deadlineNanos();
        TraversalState state = new TraversalState(deadline);
        List<ValueRef> resolvedTargets = resolveTargets(graph, targetExpression);
        if (resolvedTargets.isEmpty()) {
            LineageIndex emptyIndex = LineageIndex.build(graph, Set.of());
            return resultMerger.noMatch(
                    emptyIndex,
                    targetExpression,
                    direction,
                    freshness,
                    "FIELD_NOT_FOUND",
                    "The method does not declare this value or field"
            );
        }
        ObjectExpansion expansion = expandObjectTargets(
                graph,
                resolvedTargets,
                budget,
                deadline
        );
        if (expansion.truncationReason() != null) {
            state.markTruncated(expansion.truncationReason());
        }
        List<ValueRef> targets = expansion.targets();
        if (targets.isEmpty()) {
            targets = resolvedTargets;
            state.markTruncated("TIME_LIMIT");
        }
        List<ValueRef> forwardTargets = forwardTargets(graph, resolvedTargets, targets);
        if (budget.expired(deadline)) {
            state.markTruncated("TIME_LIMIT");
        }
        LineageIndex index = LineageIndex.build(
                graph,
                targets.stream().map(ValueRef::nodeId).collect(java.util.stream.Collectors.toSet())
        );
        if (budget.expired(deadline)) {
            state.markTruncated("TIME_LIMIT");
        }

        boolean includeIn = direction.equals("in") || direction.equals("both");
        boolean includeOut = direction.equals("out") || direction.equals("both");
        /*
         * "both" is deliberately the union of two independent cones:
         * target -> upstream and target -> downstream. Sharing one visited set
         * between them turns the query into a connected-component walk and
         * explodes on real call graphs.
         */
        if (includeIn && includeOut) {
            traverseBoth(index, targets, forwardTargets, budget, state);
        } else if (includeIn) {
            traverse(index, targets, true, budget, state);
        } else {
            traverse(index, forwardTargets, false, budget, state);
        }

        return resultMerger.merge(
                index,
                state,
                targets.get(0),
                targetExpression,
                direction,
                freshness,
                ambiguousCandidates,
                weakHops,
                includeIn,
                includeOut
        );
    }

    /**
     * Reachable callee-scope field refs are needed to recover the IN cone of a
     * selected field. Seeding every callee with the same field for OUT would
     * duplicate each downstream use once per method boundary, so a field query
     * keeps only the user-selected ref on the forward side.
     */
    // 作用：计算正向遍历的起点集合；逻辑：展开对象字段、过滤不可达与内部版本节点后生成字段级引用。
    private List<ValueRef> forwardTargets(
            LineageGraph graph,
            List<ValueRef> resolvedTargets,
            List<ValueRef> expandedTargets
    ) {
        boolean fieldTarget = resolvedTargets.stream().anyMatch(ref -> {
            if (!ref.fields().isEmpty()) {
                return true;
            }
            return graph.valueNodes().stream()
                    .filter(node -> node.valueId().equals(ref.nodeId()))
                    .findFirst()
                    .map(node -> node.accessPath() != null && !node.accessPath().isEmpty())
                    .orElse(false);
        });
        return fieldTarget ? resolvedTargets : expandedTargets;
    }

    /**
     * An object target means "every field of this object". Expanding it into
     * field refs up front keeps the query bounded and mirrors what the
     * {@code targets} command reports. Without this step an object trace would
     * walk into every downstream object it meets and treat their fields as
     * part of the selected object's lineage.
     */
    // 作用：展开对象目标的全部字段；逻辑：收集可达具体字段与对象根，生成字段级遍历起点。
    private ObjectExpansion expandObjectTargets(
            LineageGraph graph,
            List<ValueRef> resolvedTargets,
            TraversalBudget budget,
            long deadline
    ) {
        ObjectExpansion expansion = new ObjectExpansion(deadline, Math.max(1, budget.maxEdges()));
        Set<String> evidenceNodeIds = new HashSet<>();
        for (DataEdgeRecord edge : graph.dataEdges()) {
            if (expansion.stopped()) {
                break;
            }
            evidenceNodeIds.add(edge.srcValueId());
            evidenceNodeIds.add(edge.dstValueId());
        }
        Map<String, ValueNodeRecord> nodesById = new LinkedHashMap<>();
        Map<String, List<ValueNodeRecord>> nodesByScope = new LinkedHashMap<>();
        Map<String, List<ValueNodeRecord>> parametersByScope = new LinkedHashMap<>();
        for (ValueNodeRecord node : graph.valueNodes()) {
            if (expansion.stopped()) {
                break;
            }
            nodesById.put(node.valueId(), node);
            nodesByScope.computeIfAbsent(node.scopeSymbolId(), ignored -> new ArrayList<>()).add(node);
            if ("PARAMETER".equals(node.valueKind())) {
                parametersByScope
                        .computeIfAbsent(node.scopeSymbolId(), ignored -> new ArrayList<>())
                        .add(node);
            }
        }
        parametersByScope.values().forEach(parameters -> parameters.sort(
                Comparator.comparingInt(ValueNodeRecord::lineStart)
                        .thenComparingInt(ValueNodeRecord::columnStart)
        ));
        Map<String, List<DataEdgeRecord>> dataEdgesBySource = new LinkedHashMap<>();
        for (DataEdgeRecord edge : graph.dataEdges()) {
            if (expansion.stopped()) {
                break;
            }
            dataEdgesBySource.computeIfAbsent(edge.srcValueId(), ignored -> new ArrayList<>()).add(edge);
        }
        Map<String, List<CallArgument>> callArgumentsByValue = new LinkedHashMap<>();
        for (CallEdgeRecord call : graph.callEdges()) {
            if (expansion.stopped()) {
                break;
            }
            if (call.calleeSymbolId() == null) {
                continue;
            }
            for (int index = 0; index < call.argumentValueIds().size(); index++) {
                String valueId = call.argumentValueIds().get(index);
                if (valueId != null) {
                    callArgumentsByValue
                            .computeIfAbsent(valueId, ignored -> new ArrayList<>())
                            .add(new CallArgument(call, index));
                }
            }
        }
        for (ValueRef resolved : resolvedTargets) {
            if (expansion.stopped()) {
                break;
            }
            if (!resolved.fields().isEmpty()) {
                expansion.add(resolved);
                /*
                 * A selected field must follow the same real object hand-offs
                 * as an object target. Otherwise an object query sees fields
                 * read in callees while {@code object.field} only sees its
                 * local OUT cone and appears to have lost its IN half.
                 */
                for (String rootId : reachableObjectRootIds(
                        resolved.nodeId(),
                        nodesById,
                        parametersByScope,
                        dataEdgesBySource,
                        callArgumentsByValue,
                        expansion
                )) {
                    if (expansion.stopped()) {
                        break;
                    }
                    if (!rootId.equals(resolved.nodeId())) {
                        expansion.add(new ValueRef(rootId, resolved.fields()));
                    }
                }
                reachableConcreteFieldTargets(
                        graph,
                        resolved,
                        nodesById,
                        expansion
                );
                continue;
            }
            if (!nodesById.containsKey(resolved.nodeId())) {
                expansion.add(resolved);
                continue;
            }
            for (String rootId : reachableObjectRootIds(
                    resolved.nodeId(),
                    nodesById,
                    parametersByScope,
                    dataEdgesBySource,
                    callArgumentsByValue,
                    expansion
            )) {
                if (expansion.stopped()) {
                    break;
                }
                ValueNodeRecord root = nodesById.get(rootId);
                if (root == null) {
                    continue;
                }
                expansion.add(rootId.equals(resolved.nodeId())
                        ? resolved
                        : new ValueRef(rootId, List.of()));
                fieldPaths(
                        root,
                        nodesByScope.getOrDefault(root.scopeSymbolId(), List.of()),
                        evidenceNodeIds,
                        expansion
                ).stream()
                        .sorted(Comparator.comparing(path -> String.join(".", path)))
                        .forEach(path -> expansion.add(new ValueRef(rootId, path)));
            }
        }
        return expansion;
    }

    /**
     * A selected element field such as {@code tabs[*].defaultOn} is written
     * inside callee scopes against their own {@code tabs[*]} carrier. Those
     * carriers are not object roots for call-argument binding, so add each
     * evidence-backed root with the same field path. The scope set is already
     * limited to the graph reachable from the requested method.
     */
    // 作用：收集可达的具体字段目标；逻辑：沿字段路径与证据边递归查找并写入结果集。
    private void reachableConcreteFieldTargets(
            LineageGraph graph,
            ValueRef resolved,
            Map<String, ValueNodeRecord> nodesById,
            ObjectExpansion expansion
    ) {
        ValueNodeRecord selected = nodesById.get(resolved.nodeId());
        if (selected == null
                || selected.rootName() == null
                || selected.rootName().isBlank()) {
            return;
        }
        String rootName = selected.rootName();
        Set<String> matchingScopes = new HashSet<>();
        for (ValueNodeRecord field : graph.valueNodes()) {
            if (expansion.stopped()) {
                return;
            }
            if ("FIELD".equals(field.valueKind())
                    && rootName.equals(field.rootName())
                    && resolved.fields().equals(field.accessPath())) {
                matchingScopes.add(field.scopeSymbolId());
            }
        }
        for (ValueNodeRecord root : graph.valueNodes()) {
            if (expansion.stopped()) {
                return;
            }
            if (!matchingScopes.contains(root.scopeSymbolId())
                    || !rootName.equals(root.canonicalValue())
                    || root.accessPath() != null && !root.accessPath().isEmpty()) {
                continue;
            }
            expansion.add(new ValueRef(root.valueId(), resolved.fields()));
        }
    }

    // 作用：枚举节点上的字段访问路径；逻辑：从字段展开索引提取路径并去重。
    private Set<List<String>> fieldPaths(
            ValueNodeRecord root,
            List<ValueNodeRecord> scopeNodes,
            Set<String> evidenceNodeIds,
            ObjectExpansion expansion
    ) {
        Set<List<String>> paths = new LinkedHashSet<>();
        for (ValueNodeRecord node : scopeNodes) {
            if (expansion.stopped()) {
                break;
            }
            if (!"FIELD".equals(node.valueKind())
                    || node.accessPath() == null
                    || node.accessPath().isEmpty()
                    || node.rootName() == null
                    || !node.rootName().equals(root.canonicalValue())
                    || !evidenceNodeIds.contains(node.valueId())
                    || isInternalVersion(node)) {
                continue;
            }
            paths.add(List.copyOf(node.accessPath()));
        }
        return paths;
    }

    /**
     * Follows only real object hand-offs: unchanged parameter/local aliases and
     * direct call arguments. Same-named parameters in unrelated reachable
     * scopes are therefore not treated as the selected object.
     */
    // 作用：收集可达对象根 ID；逻辑：从目标节点沿对象载体边逐层扩展。
    private Set<String> reachableObjectRootIds(
            String selectedRootId,
            Map<String, ValueNodeRecord> nodesById,
            Map<String, List<ValueNodeRecord>> parametersByScope,
            Map<String, List<DataEdgeRecord>> dataEdgesBySource,
            Map<String, List<CallArgument>> callArgumentsByValue,
            ObjectExpansion expansion
    ) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        result.add(selectedRootId);
        queue.add(selectedRootId);
        while (!queue.isEmpty() && !expansion.stopped()) {
            String current = queue.poll();
            for (CallArgument binding : callArgumentsByValue.getOrDefault(current, List.of())) {
                if (expansion.stopped()) {
                    break;
                }
                List<ValueNodeRecord> parameters =
                        parametersByScope.getOrDefault(binding.call().calleeSymbolId(), List.of());
                if (binding.index() >= parameters.size()) {
                    continue;
                }
                ValueNodeRecord parameter = parameters.get(binding.index());
                if (isObjectCarrier(parameter) && result.add(parameter.valueId())) {
                    queue.add(parameter.valueId());
                }
            }
            for (DataEdgeRecord edge : dataEdgesBySource.getOrDefault(current, List.of())) {
                if (expansion.stopped()) {
                    break;
                }
                ValueNodeRecord target = nodesById.get(edge.dstValueId());
                if (isObjectCarrier(target) && result.add(target.valueId())) {
                    queue.add(target.valueId());
                }
            }
        }
        return result;
    }

    private record CallArgument(CallEdgeRecord call, int index) {
    }

    // 作用：判断节点是否承载对象字段；逻辑：节点类型属于对象或容器载体时返回 true。
    private boolean isObjectCarrier(ValueNodeRecord node) {
        return node != null
                && ("PARAMETER".equals(node.valueKind()) || "LOCAL".equals(node.valueKind()))
                && (node.accessPath() == null || node.accessPath().isEmpty());
    }

    // 作用：判断是否为内部版本节点；逻辑：节点值以内部版本前缀命名时视为实现细节。
    private boolean isInternalVersion(ValueNodeRecord node) {
        String value = node.canonicalValue();
        return value.startsWith("branch:")
                || value.startsWith("phi:")
                || value.startsWith("before:");
    }

    // 作用：执行单方向遍历主循环；逻辑：初始化种子、逐层处理前沿，记录边并受预算约束。
    private void traverse(
            LineageIndex index,
            List<ValueRef> targets,
            boolean backward,
            TraversalBudget budget,
            TraversalState state
    ) {
        TraversalFrontier frontier = new TraversalFrontier();
        Deque<ValueRef> queue = new ArrayDeque<>();
        for (ValueRef target : targets) {
            frontier.visited.add(target);
            frontier.depth.put(target, 0);
            state.visited.add(target);
            if (backward) {
                state.backwardVisited.add(target);
            }
            queue.add(target);
        }
        while (!queue.isEmpty()) {
            if (budget.expired(state.deadline)) {
                state.markTruncated("TIME_LIMIT");
                return;
            }
            ValueRef current = queue.poll();
            if (backward) {
                state.backwardVisited.add(current);
            }
            int currentDepth = frontier.depth.getOrDefault(current, 0);
            List<Step> steps = backward ? index.incoming(current) : index.outgoing(current);
            if (steps.isEmpty()) {
                continue;
            }
            if (currentDepth >= budget.maxDepth()) {
                state.markTruncated("DEPTH_LIMIT");
                continue;
            }
            if (backward) {
                queue.addAll(backwardTraversal.expand(index, current, steps, currentDepth, budget, state, frontier));
            } else {
                queue.addAll(forwardTraversal.expand(index, current, steps, currentDepth, budget, state, frontier));
            }
        }
    }

    /**
     * Expands the two cones fairly. Running the complete upstream cone before
     * the downstream cone lets a large set of producers consume the shared
     * edge budget and makes {@code direction=both} appear to be IN-only for
     * real projects. Round-robin expansion preserves the same budget contract
     * while guaranteeing that both directions get a chance to contribute.
     */
    // 作用：执行双向遍历主循环；逻辑：同时维护正反两个前沿，交替扩展并合并结果。
    private void traverseBoth(
            LineageIndex index,
            List<ValueRef> backwardTargets,
            List<ValueRef> forwardTargets,
            TraversalBudget budget,
            TraversalState state
    ) {
        TraversalFrontier backwardFrontier = new TraversalFrontier();
        TraversalFrontier forwardFrontier = new TraversalFrontier();
        Deque<ValueRef> backwardQueue = seed(backwardFrontier, backwardTargets, state, true);
        Deque<ValueRef> forwardQueue = seed(forwardFrontier, forwardTargets, state, false);
        boolean preferBackward = true;

        while (!backwardQueue.isEmpty() || !forwardQueue.isEmpty()) {
            if (budget.expired(state.deadline)) {
                state.markTruncated("TIME_LIMIT");
                return;
            }
            boolean processed = preferBackward
                    ? processNext(index, backwardQueue, backwardFrontier, true, budget, state)
                    : processNext(index, forwardQueue, forwardFrontier, false, budget, state);
            if (!processed) {
                processed = preferBackward
                        ? processNext(index, forwardQueue, forwardFrontier, false, budget, state)
                        : processNext(index, backwardQueue, backwardFrontier, true, budget, state);
            }
            if (!processed) {
                return;
            }
            preferBackward = !preferBackward;
        }
    }

    // 作用：构造遍历种子队列；逻辑：按方向加入目标引用并去重。
    private Deque<ValueRef> seed(
            TraversalFrontier frontier,
            List<ValueRef> targets,
            TraversalState state,
            boolean backward
    ) {
        Deque<ValueRef> queue = new ArrayDeque<>();
        for (ValueRef target : targets) {
            frontier.visited.add(target);
            frontier.depth.put(target, 0);
            state.visited.add(target);
            if (backward) {
                state.backwardVisited.add(target);
            }
            queue.add(target);
        }
        return queue;
    }

    // 作用：处理前沿中的一个节点；逻辑：检查预算与访问状态，扩展下一步并记录结果。
    private boolean processNext(
            LineageIndex index,
            Deque<ValueRef> queue,
            TraversalFrontier frontier,
            boolean backward,
            TraversalBudget budget,
            TraversalState state
    ) {
        ValueRef current = queue.poll();
        if (current == null) {
            return false;
        }
        if (backward) {
            state.backwardVisited.add(current);
        }
        int currentDepth = frontier.depth.getOrDefault(current, 0);
        List<Step> steps = backward ? index.incoming(current) : index.outgoing(current);
        if (steps.isEmpty()) {
            return true;
        }
        if (currentDepth >= budget.maxDepth()) {
            state.markTruncated("DEPTH_LIMIT");
            return true;
        }
        queue.addAll(backward
                ? backwardTraversal.expand(index, current, steps, currentDepth, budget, state, frontier)
                : forwardTraversal.expand(index, current, steps, currentDepth, budget, state, frontier));
        return true;
    }

    // 作用：把目标表达式解析为候选引用；逻辑：依次尝试精确字段、构造器别名与唯一节点。
    private List<ValueRef> resolveTargets(LineageGraph graph, String targetExpression) {
        String methodId = graph.method().methodId();
        List<ValueNodeRecord> exact = graph.valueNodes().stream()
                .filter(node -> node.scopeSymbolId().equals(methodId))
                .filter(node -> node.canonicalValue().equals(targetExpression))
                .toList();
        if (!exact.isEmpty()) {
            return latestByCanonical(exact).stream()
                    .map(node -> exactFieldRef(graph, node))
                    .toList();
        }
        ValueRef lambdaElementAlias = lambdaElementAliasResolver.resolve(
                methodId,
                targetExpression,
                graph.valueNodes(),
                graph.dataEdges()
        );
        if (lambdaElementAlias != null) {
            return List.of(lambdaElementAlias);
        }
        ParsedTarget parsed = ParsedTarget.parse(targetExpression);
        List<ValueRef> constructorAliasTargets = resolveConstructorAliasTargets(
                graph, methodId, parsed
        );
        if (!constructorAliasTargets.isEmpty()) {
            return constructorAliasTargets;
        }
        if (!parsed.fields().isEmpty()
                && !hasKnownFieldPath(graph, methodId, parsed.base(), parsed.fields())) {
            return List.of();
        }
        List<ValueNodeRecord> parsedNodes = graph.valueNodes().stream()
                .filter(node -> node.scopeSymbolId().equals(methodId))
                .filter(node -> node.canonicalValue().equals(parsed.base()))
                .toList();
        return latestByCanonical(parsedNodes).stream()
                .map(node -> new ValueRef(node.valueId(), parsed.fields()))
                .toList();
    }

    /**
     * Exact target lookup may find the concrete FIELD node directly. Traversal
     * operates on the owning object plus a field path so both incoming field
     * writes and outgoing container projections use the same representation.
     * Keeping the concrete node as an object root would expose its producers as
     * isolated leaves without connecting their evidence edges.
     */
    // 作用：构造精确字段引用；逻辑：把节点规范值拆成根与字段路径后生成引用。
    private ValueRef exactFieldRef(LineageGraph graph, ValueNodeRecord node) {
        if (!"FIELD".equals(node.valueKind())
                || node.accessPath() == null
                || node.accessPath().isEmpty()
                || node.rootName() == null
                || "this".equals(node.rootName())) {
            return new ValueRef(node.valueId(), List.of());
        }
        ValueNodeRecord root = graph.valueNodes().stream()
                .filter(candidate -> candidate.scopeSymbolId().equals(node.scopeSymbolId()))
                .filter(candidate -> node.rootName().equals(candidate.canonicalValue()))
                .filter(candidate -> candidate.accessPath() == null
                        || candidate.accessPath().isEmpty())
                .filter(candidate -> candidate.declarationOffset() <= node.declarationOffset())
                .max(Comparator
                        .comparingInt(ValueNodeRecord::declarationOffset)
                        .thenComparingInt(ValueNodeRecord::definitionOrdinal))
                .orElse(null);
        return root == null
                ? new ValueRef(node.valueId(), List.of())
                : new ValueRef(root.valueId(), List.copyOf(node.accessPath()));
    }

    /**
     * Constructor results are stored under their constructor identity
     * ({@code new:Type} and, for returns, {@code RETURN}). Users naturally ask
     * for the business type and field, for example
     * {@code ActivityAndBailInfo.count}. Resolve that spelling only when the
     * selected method creates exactly one matching constructor and the
     * constructor result reaches a single concrete root with the requested
     * field evidence.
     */
    // 作用：解析构造器别名目标；逻辑：匹配构造器参数与字段写入关系，生成别名引用。
    private List<ValueRef> resolveConstructorAliasTargets(
            LineageGraph graph,
            String methodId,
            ParsedTarget parsed
    ) {
        if (parsed.fields().isEmpty() || parsed.base().startsWith("new:")) {
            return List.of();
        }
        List<ValueNodeRecord> constructorResults = graph.valueNodes().stream()
                .filter(node -> methodId.equals(node.scopeSymbolId()))
                .filter(node -> "CALL_RESULT".equals(node.valueKind()))
                .filter(node -> constructorTypeMatches(node.canonicalValue(), parsed.base()))
                .toList();
        if (constructorResults.size() != 1) {
            return List.of();
        }
        String constructorId = constructorResults.get(0).valueId();
        List<String> rootValues = graph.dataEdges().stream()
                .filter(edge -> constructorId.equals(edge.srcValueId()))
                .map(edge -> nodeById(graph, edge.dstValueId()))
                .filter(node -> node != null
                        && (node.accessPath() == null || node.accessPath().isEmpty()))
                .map(ValueNodeRecord::canonicalValue)
                .distinct()
                .toList();
        if (rootValues.size() != 1) {
            return List.of();
        }
        String root = rootValues.get(0);
        if (!hasKnownFieldPath(graph, methodId, root, parsed.fields())) {
            return List.of();
        }
        List<ValueNodeRecord> rootNodes = graph.valueNodes().stream()
                .filter(node -> methodId.equals(node.scopeSymbolId()))
                .filter(node -> root.equals(node.canonicalValue()))
                .toList();
        return latestByCanonical(rootNodes).stream()
                .map(node -> new ValueRef(node.valueId(), parsed.fields()))
                .toList();
    }

    // 作用：判断构造器类型是否匹配目标；逻辑：比较规范值与期望类型的后缀。
    private boolean constructorTypeMatches(String canonicalValue, String requestedType) {
        if (canonicalValue == null || !canonicalValue.startsWith("new:")) {
            return false;
        }
        String declared = canonicalValue.substring("new:".length());
        int generic = declared.indexOf('<');
        if (generic >= 0) {
            declared = declared.substring(0, generic);
        }
        int separator = declared.lastIndexOf('.');
        String declaredSimple = separator < 0 ? declared : declared.substring(separator + 1);
        int requestedSeparator = requestedType.lastIndexOf('.');
        String requestedSimple = requestedSeparator < 0
                ? requestedType
                : requestedType.substring(requestedSeparator + 1);
        return declared.equals(requestedType)
                || declaredSimple.equals(requestedType)
                || declared.equals(requestedSimple)
                || declaredSimple.equals(requestedSimple);
    }

    // 作用：按 ID 查找节点；逻辑：查图节点表，缺失时返回 null。
    private ValueNodeRecord nodeById(LineageGraph graph, String nodeId) {
        return graph.valueNodes().stream()
                .filter(node -> node.valueId().equals(nodeId))
                .findFirst()
                .orElse(null);
    }

    /**
     * A dotted target is only accepted when at least one analysed scope has an
     * evidence-backed FIELD node with exactly that root and access path. Without
     * this guard an arbitrary suffix such as {@code notAField} would propagate
     * across call bridges and invent lineage that does not exist.
     */
    // 作用：判断节点是否已知该字段路径；逻辑：检查字段展开索引与证据边是否覆盖该路径。
    private boolean hasKnownFieldPath(
            LineageGraph graph,
            String methodId,
            String root,
            List<String> fields
    ) {
        Set<String> evidenceNodeIds = evidenceNodeIds(graph.dataEdges());
        boolean exactFieldEvidence = graph.valueNodes().stream()
                .filter(node -> "FIELD".equals(node.valueKind()))
                .filter(node -> methodId.equals(node.scopeSymbolId()))
                .filter(node -> root.equals(node.rootName()))
                .filter(node -> fields.equals(node.accessPath()))
                .filter(node -> evidenceNodeIds.contains(node.valueId()))
                .findAny()
                .isPresent();
        if (exactFieldEvidence) {
            return true;
        }
        /*
         * Object parameters are often only expanded in callees. autoPay, for
         * example, passes orderRequest to buildInvoice, where addressId is
         * actually read. The graph already contains only the root method's
         * reachable scopes, so evidence for the same root/path in one of those
         * scopes proves the selected field exists without accepting arbitrary
         * suffixes.
         */
        boolean reachableFieldEvidence = graph.valueNodes().stream()
                .filter(node -> "FIELD".equals(node.valueKind()))
                .filter(node -> root.equals(node.rootName()))
                .filter(node -> fields.equals(node.accessPath()))
                .filter(node -> evidenceNodeIds.contains(node.valueId()))
                .findAny()
                .isPresent();
        if (reachableFieldEvidence) {
            return true;
        }
        if (methodReferenceResultCarriesField(graph, methodId, root, fields)) {
            return true;
        }
        if ("RETURN".equals(root) && returnCarriesField(
                graph, methodId, root, fields, evidenceNodeIds
        )) {
            return true;
        }
        // Bean copy deliberately carries every matching property through one
        // COPY_ALL edge. In that contract the property may be visible on only
        // one side of the copy, so accept it only when the copied counterpart
        // has evidence for the exact access path. A field on an unrelated root
        // is not proof that this object owns the property.
        Map<String, ValueNodeRecord> nodesById = new LinkedHashMap<>();
        for (ValueNodeRecord node : graph.valueNodes()) {
            nodesById.put(node.valueId(), node);
        }
        return graph.dataEdges().stream()
                .filter(edge -> "COPY_ALL".equals(edge.edgeType()))
                .anyMatch(edge -> copyCarriesField(edge, root, fields, nodesById));
    }

    /**
     * A method commonly assembles a local object and returns it:
     * {@code TabInfo local = new TabInfo(); ...; return local;}. The return
     * node itself has no field child, but its incoming object assignment does.
     * Accept {@code RETURN.field} only when that assigned object has concrete
     * evidence for the same field.
     */
    // 作用：判断返回值是否携带目标字段；逻辑：追踪返回节点的来源字段并做路径匹配。
    private boolean returnCarriesField(
            LineageGraph graph,
            String methodId,
            String returnRoot,
            List<String> fields,
            Set<String> evidenceNodeIds
    ) {
        Map<String, ValueNodeRecord> nodesById = new LinkedHashMap<>();
        for (ValueNodeRecord node : graph.valueNodes()) {
            nodesById.put(node.valueId(), node);
        }
        for (DataEdgeRecord edge : graph.dataEdges()) {
            ValueNodeRecord target = nodesById.get(edge.dstValueId());
            if (target == null
                    || !methodId.equals(target.scopeSymbolId())
                    || !returnRoot.equals(target.canonicalValue())) {
                continue;
            }
            ValueNodeRecord source = nodesById.get(edge.srcValueId());
            if (source == null
                    || source.accessPath() != null && !source.accessPath().isEmpty()) {
                continue;
            }
            boolean sourceHasField = graph.valueNodes().stream()
                    .filter(node -> "FIELD".equals(node.valueKind()))
                    .filter(node -> source.scopeSymbolId().equals(node.scopeSymbolId()))
                    .filter(node -> source.canonicalValue().equals(node.rootName()))
                    .filter(node -> fields.equals(node.accessPath()))
                    .anyMatch(node -> evidenceNodeIds.contains(node.valueId()));
            if (sourceHasField) {
                return true;
            }
        }
        return false;
    }

    // 作用：判断方法引用结果是否携带字段；逻辑：解析引用目标并检查返回值字段。
    private boolean methodReferenceResultCarriesField(
            LineageGraph graph,
            String methodId,
            String root,
            List<String> fields
    ) {
        return graph.callEdges().stream()
                .filter(call -> methodId.equals(call.callerSymbolId()))
                .filter(call -> "METHOD_REFERENCE".equals(call.dispatchKind()))
                .filter(call -> call.calleeSymbolId() != null && call.resultValueId() != null)
                .anyMatch(call -> root.equals(nodeCanonicalValue(graph, call.resultValueId()))
                        && calleeHasFieldEvidence(graph, call.calleeSymbolId(), fields));
    }

    // 作用：取节点的规范值；逻辑：查节点表，缺失时返回空串。
    private String nodeCanonicalValue(LineageGraph graph, String nodeId) {
        ValueNodeRecord node = nodeById(graph, nodeId);
        return node == null ? null : node.canonicalValue();
    }

    // 作用：判断被调方是否有字段证据；逻辑：检查调用结果关联的证据节点是否存在。
    private boolean calleeHasFieldEvidence(
            LineageGraph graph,
            String calleeSymbolId,
            List<String> fields
    ) {
        Set<String> evidenceNodeIds = evidenceNodeIds(graph.dataEdges());
        return graph.valueNodes().stream()
                .filter(node -> calleeSymbolId.equals(node.scopeSymbolId()))
                .filter(node -> "FIELD".equals(node.valueKind()))
                .filter(node -> fields.equals(node.accessPath()))
                .filter(node -> evidenceNodeIds.contains(node.valueId()))
                .findAny()
                .isPresent();
    }

    // 作用：收集边上的证据节点；逻辑：遍历数据边并汇总证据标识。
    private Set<String> evidenceNodeIds(List<DataEdgeRecord> edges) {
        Set<String> result = new HashSet<>();
        for (DataEdgeRecord edge : edges) {
            result.add(edge.srcValueId());
            result.add(edge.dstValueId());
        }
        return result;
    }

    // 作用：判断拷贝操作是否携带字段；逻辑：比较源与目标的字段路径是否匹配。
    private boolean copyCarriesField(
            DataEdgeRecord edge,
            String root,
            List<String> fields,
            Map<String, ValueNodeRecord> nodesById
    ) {
        ValueNodeRecord source = nodesById.get(edge.srcValueId());
        ValueNodeRecord target = nodesById.get(edge.dstValueId());
        if (root.equals(canonicalRoot(source, ""))) {
            return hasFieldPath(target, fields);
        }
        if (root.equals(canonicalRoot(target, ""))) {
            return hasFieldPath(source, fields);
        }
        return false;
    }

    // 作用：判断节点是否包含指定字段路径；逻辑：比较节点规范值的路径后缀。
    private boolean hasFieldPath(ValueNodeRecord node, List<String> fields) {
        return node != null
                && "FIELD".equals(node.valueKind())
                && fields.equals(node.accessPath());
    }

    // 作用：取节点的规范根；逻辑：优先使用规范值，缺失时回退到传入值。
    private String canonicalRoot(ValueNodeRecord node, String fallback) {
        return node == null || node.rootName() == null || node.rootName().isBlank()
                ? fallback
                : node.rootName();
    }

    // 作用：按规范值保留最新节点；逻辑：分组后取语句序号最大的版本。
    private List<ValueNodeRecord> latestByCanonical(List<ValueNodeRecord> nodes) {
        Map<String, ValueNodeRecord> latest = new LinkedHashMap<>();
        nodes.stream()
                .sorted(Comparator
                        .comparingInt(ValueNodeRecord::lineStart)
                        .thenComparingInt(ValueNodeRecord::columnStart)
                        .thenComparingInt(ValueNodeRecord::definitionOrdinal))
                .forEach(node -> latest.put(node.canonicalValue(), node));
        return List.copyOf(latest.values());
    }

    /**
     * Mutable expansion accumulator. Object queries can generate thousands of
     * field seeds before traversal starts; the deadline and seed cap keep that
     * phase inside the same budget contract as the traversal itself.
     */
    private static final class ObjectExpansion {
        private final Set<ValueRef> targets = new LinkedHashSet<>();
        private final long deadline;
        private final int maxSeeds;
        private String truncationReason;

        // 作用：创建对象字段展开累加器；逻辑：保存截止时间与最大种子数量。
        private ObjectExpansion(long deadline, int maxSeeds) {
            this.deadline = deadline;
            this.maxSeeds = maxSeeds;
        }

        // 作用：判断展开是否应停止；逻辑：首次超时记录时间限制原因，已存在截断原因时直接返回真。
        private boolean stopped() {
            if (truncationReason == null && System.nanoTime() > deadline) {
                truncationReason = "TIME_LIMIT";
            }
            return truncationReason != null;
        }

        // 作用：追加一个字段级遍历起点；逻辑：检查超时与种子上限，超限时记录原因而不加入。
        private void add(ValueRef ref) {
            if (stopped()) {
                return;
            }
            if (System.nanoTime() > deadline) {
                truncationReason = "TIME_LIMIT";
                return;
            }
            if (targets.size() >= maxSeeds) {
                truncationReason = "SEED_LIMIT";
                return;
            }
            targets.add(ref);
        }

        // 作用：导出已展开目标；逻辑：返回种子集合的不可变副本。
        private List<ValueRef> targets() {
            return List.copyOf(targets);
        }

        // 作用：读取截断原因；逻辑：返回时间或种子上限导致停止的原因，未截断时为空。
        private String truncationReason() {
            return truncationReason;
        }
    }

    private record ParsedTarget(String base, List<String> fields) {
        // 作用：解析目标表达式；逻辑：按首个点号拆出根对象与后续字段路径，无点号时字段列表为空。
        static ParsedTarget parse(String expression) {
            int dot = expression.indexOf('.');
            if (dot < 0) {
                return new ParsedTarget(expression, List.of());
            }
            String base = expression.substring(0, dot);
            List<String> fields = List.of(expression.substring(dot + 1).split("\\."));
            return new ParsedTarget(base, fields);
        }
    }
}
