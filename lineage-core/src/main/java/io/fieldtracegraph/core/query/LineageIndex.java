package io.fieldtracegraph.core.query;

import io.fieldtracegraph.core.model.CallEdgeRecord;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.EdgeEvidence;
import io.fieldtracegraph.core.model.MethodCandidate;
import io.fieldtracegraph.core.model.Provenance;
import io.fieldtracegraph.core.model.Resolution;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.TraceNode;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

// 作用：内存中的血缘图索引，是遍历的起点。
// 使用场景：查询服务加载快照后构建，供各遍历器读取。

/**
 * Immutable request-scoped index built from a {@link LineageGraph}. It holds
 * value nodes, source files, data edges and the call-site bridges that join
 * caller and callee scopes. Traversals read from this index instead of the
 * storage adapter.
 */
final class LineageIndex {
    private final LineageGraph graph;
    private final Map<String, ValueNodeRecord> nodesById = new LinkedHashMap<>();
    private final Map<String, SourceFileRecord> filesById = new HashMap<>();
    private final Map<String, List<Step>> incomingByNode = new HashMap<>();
    private final Map<String, List<Step>> outgoingByNode = new HashMap<>();
    private final Map<String, List<FieldExpansion>> fieldExpansionsByNode = new HashMap<>();
    private final Set<String> fieldNodeIdsWithEvidence = new HashSet<>();
    private final Set<String> objectValuedFieldIds = new HashSet<>();
    private final Set<String> objectValuedFieldPaths = new HashSet<>();
    private final Map<String, Step> stepByEvidenceId = new HashMap<>();
    private final Map<String, List<Step>> callProducerStepsByResultNode = new HashMap<>();
    private final Map<String, List<ValueNodeRecord>> parametersByScope = new HashMap<>();
    private final Map<String, List<ValueNodeRecord>> callParametersByArgumentNode = new HashMap<>();
    private final Map<String, List<CallEdgeRecord>> callsByResultNode = new HashMap<>();
    private final Set<String> unresolvedConsumerNodeIds = new HashSet<>();
    private final Set<String> callConsumerNodeIds = new HashSet<>();
    private final Map<String, List<ValueNodeRecord>> returnsByScope = new HashMap<>();
    private final Map<String, List<ValueNodeRecord>> dataSourcesByDestination = new HashMap<>();
    private final Set<String> supportedFieldPaths = new HashSet<>();
    private final Set<String> fieldNodeKeys = new HashSet<>();
    private final Map<String, List<ValueNodeRecord>> objectNodesByScopeAndName = new HashMap<>();
    private final Map<String, List<ValueNodeRecord>> fieldNodesByScopeAndPath = new HashMap<>();
    private final Set<String> containerElementKeys = new HashSet<>();
    private final Set<String> externalCallResultNodeIds = new HashSet<>();
    private final Map<String, String> externalReasonByResultValueId = new LinkedHashMap<>();
    private final Set<String> expansionRootIds;
    private final int generation;
    private final String graphStatus;

    // 作用：构造内存血缘索引；逻辑：保存图、扩展根、代数与状态，后续由 build 填充各索引表。
    private LineageIndex(LineageGraph graph, Set<String> expansionRootIds, int generation, String graphStatus) {
        this.graph = graph;
        this.expansionRootIds = expansionRootIds;
        this.generation = generation;
        this.graphStatus = graphStatus;
    }

    /**
     * @param expansionRootIds object roots whose fields should be expanded when
     *                         the query targets the object as a whole. Only the
     *                         selected target is expanded; expanding every
     *                         downstream object invents sibling field paths and
     *                         turns one trace into a connected-component walk.
     */
    // 作用：从图数据构建完整索引；逻辑：装载节点与文件、建立调用索引、字段展开与出边入边关系。
    static LineageIndex build(LineageGraph graph, Set<String> expansionRootIds) {
        LineageIndex index = new LineageIndex(
                graph,
                Set.copyOf(expansionRootIds),
                graph.generation(),
                graph.graphStatus()
        );
        for (ValueNodeRecord node : graph.valueNodes()) {
            index.nodesById.put(node.valueId(), node);
            if (node.rootName() != null && node.accessPath() != null) {
                index.fieldNodeKeys.add(index.fieldNodeKey(
                        node.scopeSymbolId(),
                        node.rootName(),
                        node.accessPath()
                ));
                if (!isEmpty(node.accessPath())) {
                    index.fieldNodesByScopeAndPath
                            .computeIfAbsent(
                                    index.fieldNodeKey(
                                            node.scopeSymbolId(),
                                            node.rootName(),
                                            node.accessPath()
                                    ),
                                    ignored -> new ArrayList<>()
                            )
                            .add(node);
                }
            }
            if (isEmpty(node.accessPath())) {
                index.objectNodesByScopeAndName
                        .computeIfAbsent(
                                index.scopeAndValue(node.scopeSymbolId(), node.canonicalValue()),
                                key -> new ArrayList<>()
                        )
                        .add(node);
            }
            if ("PARAMETER".equals(node.valueKind())) {
                index.parametersByScope
                        .computeIfAbsent(node.scopeSymbolId(), key -> new ArrayList<>())
                        .add(node);
            }
            if ("RETURN".equals(node.valueKind())) {
                index.returnsByScope
                        .computeIfAbsent(node.scopeSymbolId(), key -> new ArrayList<>())
                        .add(node);
            }
            if ("CONTAINER_ELEMENT".equals(node.valueKind())
                    && node.canonicalValue().endsWith("[*]")) {
                index.containerElementKeys.add(
                        index.scopeAndValue(node.scopeSymbolId(), node.canonicalValue())
                );
            }
        }
        index.parametersByScope.values().forEach(parameters -> parameters.sort(
                Comparator.comparingInt(ValueNodeRecord::lineStart)
                        .thenComparingInt(ValueNodeRecord::columnStart)
        ));
        index.buildCallIndexes();
        for (var pendingRef : graph.pendingRefs()) {
            if (pendingRef.externalBoundary() && pendingRef.resultValueId() != null) {
                index.externalCallResultNodeIds.add(pendingRef.resultValueId());
                index.externalReasonByResultValueId.putIfAbsent(
                        pendingRef.resultValueId(),
                        externalReasonCode(pendingRef.reason())
                );
            }
        }
        for (var entry : graph.sourceFiles().entrySet()) {
            index.filesById.put(entry.getKey(), entry.getValue());
        }
        index.buildCallProducerSteps();
        Map<String, List<EdgeEvidence>> evidenceByLogicalKey = new LinkedHashMap<>();
        for (EdgeEvidence evidence : graph.edgeEvidence()) {
            evidenceByLogicalKey
                    .computeIfAbsent(evidence.logicalEdgeKey(), key -> new ArrayList<>())
                    .add(evidence);
        }
        Map<String, List<DataEdgeRecord>> logicalEdges = new LinkedHashMap<>();
        for (DataEdgeRecord edge : graph.dataEdges()) {
            index.fieldNodeIdsWithEvidence.add(edge.srcValueId());
            index.fieldNodeIdsWithEvidence.add(edge.dstValueId());
            ValueNodeRecord sourceNode = index.nodesById.get(edge.srcValueId());
            if (sourceNode != null && isEmpty(sourceNode.accessPath())) {
                index.dataSourcesByDestination
                        .computeIfAbsent(edge.dstValueId(), key -> new ArrayList<>())
                        .add(sourceNode);
            }
            logicalEdges.computeIfAbsent(edge.logicalEdgeKey(), key -> new ArrayList<>()).add(edge);
        }
        for (Map.Entry<String, List<DataEdgeRecord>> entry : logicalEdges.entrySet()) {
            DataEdgeRecord edge = preferredDataEdge(entry.getValue());
            List<EdgeEvidence> evidence = mergedEvidence(
                    entry.getKey(),
                    entry.getValue(),
                    evidenceByLogicalKey.getOrDefault(entry.getKey(), List.of())
            );
            String resolution = Resolution.aggregate(evidence.stream()
                    .map(item -> Resolution.from(item.resolution()))
                    .toList()).name();
            String provenance = Provenance.aggregate(evidence.stream()
                    .map(item -> Provenance.from(item.provenance()))
                    .toList()).name();
            ValueNodeRecord sourceNode = index.nodesById.get(edge.srcValueId());
            ValueNodeRecord targetNode = index.nodesById.get(edge.dstValueId());
            boolean propagateFields = sourceNode != null
                    && targetNode != null
                    && isEmpty(sourceNode.accessPath())
                    && isEmpty(targetNode.accessPath())
                    && canPropagateFields(edge.edgeType(), sourceNode, targetNode);
            index.addStep(edge.srcValueId(), edge.dstValueId(), new Step(
                    new ValueRef(edge.srcValueId(), List.of()),
                    new ValueRef(edge.dstValueId(), List.of()),
                    edge.edgeType(),
                    edge.operation(),
                    resolution,
                    edge.evidenceId(),
                    edge.fileId(),
                    edge.lineStart(),
                    edge.columnStart(),
                    edge.lineEnd(),
                    edge.columnEnd(),
                    propagateFields,
                    provenance,
                    evidence
            ));
        }
        index.buildFieldExpansions();
        for (CallArgumentBinder.Bridge bridge : new CallArgumentBinder().bind(graph)) {
            if (bridge.outgoingOnly()) {
                index.addOutgoingStep(bridge.step());
            } else {
                index.addStep(bridge.srcValueId(), bridge.dstValueId(), bridge.step());
            }
        }
        /*
         * Bridges carry object-valued field paths across method boundaries.
         * Object-valued detection therefore runs after bridge registration,
         * and the receiver field expansions are registered last.
         */
        index.buildObjectValuedFields();
        index.propagateObjectValuedFieldsAcrossBridges();
        index.buildObjectFieldReadExpansions();
        return index;
    }

    // 作用：从同一逻辑边的多份数据中选最优；逻辑：按解析强度与证据来源排序后取最高。
    private static DataEdgeRecord preferredDataEdge(List<DataEdgeRecord> edges) {
        return edges.stream()
                .max(Comparator
                        .comparingInt((DataEdgeRecord edge) ->
                                resolutionRank(Resolution.from(edge.resolution())))
                        .thenComparingInt(edge ->
                                provenanceRank(Provenance.from(edge.provenance())))
                        .thenComparing(DataEdgeRecord::edgeId))
                .orElseThrow();
    }

    // 作用：定义解析强度的排序权重；逻辑：精确高于运行时观测，其次候选，最后未解析。
    private static int resolutionRank(Resolution resolution) {
        return switch (resolution) {
            case RUNTIME_OBSERVED -> 4;
            case EXACT_STATIC -> 3;
            case CANDIDATE -> 2;
            case UNRESOLVED -> 1;
        };
    }

    // 作用：定义证据来源的排序权重；逻辑：运行时高于人工契约，其次字节码、生成源码、源码与框架适配。
    private static int provenanceRank(Provenance provenance) {
        return switch (provenance) {
            case RUNTIME_TRACE -> 6;
            case MANUAL_CONTRACT -> 5;
            case BYTECODE -> 4;
            case GENERATED_SOURCE -> 3;
            case SOURCE -> 2;
            case FRAMEWORK_ADAPTER -> 1;
        };
    }

    /**
     * Stored evidence is authoritative. Older snapshots or externally written
     * edges may not have an evidence row, so materialize the edge itself as
     * one source evidence item instead of dropping provenance.
     */
    // 作用：合并同一逻辑边的全部证据；逻辑：按证据 ID 去重并保持稳定顺序。
    private static List<EdgeEvidence> mergedEvidence(
            String logicalEdgeKey,
            List<DataEdgeRecord> physicalEdges,
            List<EdgeEvidence> stored
    ) {
        Map<String, EdgeEvidence> merged = new LinkedHashMap<>();
        for (EdgeEvidence evidence : stored) {
            merged.put(evidence.evidenceId(), evidence);
        }
        Set<String> representedEdges = merged.values().stream()
                .map(EdgeEvidence::sourceEdgeId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        for (DataEdgeRecord edge : physicalEdges) {
            if (representedEdges.contains(edge.edgeId())) {
                continue;
            }
            EdgeEvidence synthesized = EdgeEvidence.source(edge);
            if (!logicalEdgeKey.equals(synthesized.logicalEdgeKey())) {
                synthesized = new EdgeEvidence(
                        synthesized.evidenceId(),
                        logicalEdgeKey,
                        synthesized.sourceEdgeId(),
                        synthesized.resolution(),
                        synthesized.provenance(),
                        synthesized.evidenceKind(),
                        synthesized.adapter(),
                        synthesized.rule(),
                        synthesized.fileId(),
                        synthesized.lineStart(),
                        synthesized.columnStart(),
                        synthesized.lineEnd(),
                        synthesized.columnEnd(),
                        synthesized.detailsJson(),
                        synthesized.coverage(),
                        synthesized.observedAt(),
                        synthesized.scopeSymbolId()
                );
            }
            merged.put(synthesized.evidenceId(), synthesized);
        }
        return List.copyOf(merged.values());
    }

    // 作用：规范化外部边界原因码；逻辑：空值回退 EXTERNAL，其余统一转大写。
    private static String externalReasonCode(String reason) {
        return io.fieldtracegraph.core.model.BoundaryReason.canonicalName(reason);
    }

    // 作用：查值节点对应的外部原因；逻辑：从映射表读取，缺失时返回 null。
    String externalReasonForValue(String valueId) {
        return valueId == null ? null : externalReasonByResultValueId.get(valueId);
    }

    /**
     * A terminal value is only "unused" when no analysed call consumes it. If
     * the same carrier appears as a receiver or argument of an unresolved call
     * the honest state is "used, but the consumer body is not bridged".
     */
    // 作用：判断值是否被未桥接的调用消费；逻辑：检查根节点与具体字段节点是否出现在未解析调用的接收者或实参中。
    boolean hasUnresolvedConsumer(ValueRef ref) {
        return hasConsumer(ref, unresolvedConsumerNodeIds);
    }

    /**
     * A resolved void method has no value to consume downstream. Its
     * CALL_RESULT node is bookkeeping for the call expression, not a field or
     * leaf, so it must not appear as a terminal {@code NO_USE} boundary.
     */
    // 作用：判断调用结果是否来自已解析的 void 方法；逻辑：所有绑定调用都有方法体但没有任何返回值节点。
    boolean isVoidCallResult(String nodeId) {
        List<CallEdgeRecord> calls = callsByResultNode.getOrDefault(nodeId, List.of());
        if (calls.isEmpty()) {
            return false;
        }
        return calls.stream().allMatch(call -> {
            if (call.calleeSymbolId() == null) {
                return false;
            }
            /*
             * A RETURN node is created for every non-constructor callable, so
             * its existence is not proof of a value. A void method has a
             * RETURN node with no incoming value evidence.
             */
            return returnsByScope.getOrDefault(call.calleeSymbolId(), List.of()).stream()
                    .noneMatch(returnNode -> !incomingByNode
                            .getOrDefault(returnNode.valueId(), List.of())
                            .isEmpty());
        });
    }

    // 作用：判断值是否被任意调用消费；逻辑：与未桥接判断共用根节点与具体字段节点的查找规则。
    boolean hasCallConsumer(ValueRef ref) {
        return hasConsumer(ref, callConsumerNodeIds);
    }

    // 作用：在指定消费者集合中查值；逻辑：先查根节点，再按字段展开定位具体字段节点。
    private boolean hasConsumer(ValueRef ref, Set<String> consumers) {
        if (ref == null) {
            return false;
        }
        if (consumers.contains(ref.nodeId())) {
            return true;
        }
        for (FieldExpansion expansion : fieldExpansionsByNode.getOrDefault(ref.nodeId(), List.of())) {
            if (ref.fields().equals(expansion.path())
                    && consumers.contains(expansion.pathStep().target().nodeId())) {
                return true;
            }
        }
        ValueNodeRecord node = nodesById.get(ref.nodeId());
        return node != null && consumers.contains(node.valueId());
    }

    // 作用：建立调用相关索引；逻辑：按作用域、参数、返回值与调用结果节点分组。
    private void buildCallIndexes() {
        for (CallEdgeRecord call : graph.callEdges()) {
            /*
             * Remember every call consumer. A terminal value that is passed to
             * a call is "used", even when the call is void or its body is
             * external; only a value with no consumer at all is NO_USE.
             */
            if (call.receiverValueId() != null) {
                callConsumerNodeIds.add(call.receiverValueId());
            }
            if (call.argumentValueIds() != null) {
                for (String argumentValueId : call.argumentValueIds()) {
                    if (argumentValueId != null) {
                        callConsumerNodeIds.add(argumentValueId);
                    }
                }
            }
            if (call.calleeSymbolId() == null) {
                if (call.receiverValueId() != null) {
                    unresolvedConsumerNodeIds.add(call.receiverValueId());
                }
                if (call.argumentValueIds() != null) {
                    for (String argumentValueId : call.argumentValueIds()) {
                        if (argumentValueId != null) {
                            unresolvedConsumerNodeIds.add(argumentValueId);
                        }
                    }
                }
                continue;
            }
            if (call.resultValueId() != null) {
                callsByResultNode
                        .computeIfAbsent(call.resultValueId(), key -> new ArrayList<>())
                        .add(call);
            }
            for (int index = 0; index < call.argumentValueIds().size(); index++) {
                String argumentValueId = call.argumentValueIds().get(index);
                ValueNodeRecord argument = nodesById.get(argumentValueId);
                if (argument == null || !argument.scopeSymbolId().equals(call.callerSymbolId())) {
                    continue;
                }
                ValueNodeRecord calleeParameter = parameterAt(call.calleeSymbolId(), index);
                if (calleeParameter != null) {
                    callParametersByArgumentNode
                            .computeIfAbsent(argumentValueId, key -> new ArrayList<>())
                            .add(calleeParameter);
                }
            }
        }
    }

    // 作用：登记一条遍历步；逻辑：同时写入入边与出边索引。
    private void addStep(String srcNodeId, String dstNodeId, Step step) {
        incomingByNode.computeIfAbsent(dstNodeId, key -> new ArrayList<>()).add(step);
        outgoingByNode.computeIfAbsent(srcNodeId, key -> new ArrayList<>()).add(step);
        stepByEvidenceId.put(step.evidenceId(), step);
    }

    // 作用：登记一条出边步；逻辑：按源节点追加并保持插入顺序。
    private void addOutgoingStep(Step step) {
        outgoingByNode.computeIfAbsent(step.source().nodeId(), key -> new ArrayList<>()).add(step);
        stepByEvidenceId.put(step.evidenceId(), step);
    }

    /**
     * A call result owns the field path selected from it. When an unresolved
     * or external call has no local RETURN bridge, the receiver is still the
     * producer of that result and must remain visible at field level.
     */
    // 作用：建立调用结果的生产者步；逻辑：把调用边与返回节点关联成可遍历的步。
    private void buildCallProducerSteps() {
        for (CallEdgeRecord call : graph.callEdges()) {
            if (call.resultValueId() == null || call.receiverValueId() == null) {
                continue;
            }
            if ("CONSTRUCTOR".equals(call.dispatchKind())
                    || call.receiverValueId().equals(call.resultValueId())) {
                continue;
            }
            ValueNodeRecord result = nodesById.get(call.resultValueId());
            ValueNodeRecord receiver = nodesById.get(call.receiverValueId());
            if (result == null || receiver == null || !"CALL_RESULT".equals(result.valueKind())) {
                continue;
            }
            String evidenceId = Ids.sha256(
                    call.callSiteId(),
                    "call-producer",
                    call.resultValueId(),
                    call.receiverValueId()
            );
            Step step = new Step(
                    new ValueRef(call.receiverValueId(), List.of()),
                    new ValueRef(call.resultValueId(), List.of()),
                    "CALL_PRODUCER",
                    "call result of " + receiver.canonicalValue() + "." + methodName(call.targetQname()),
                    call.resolution(),
                    evidenceId,
                    call.fileId(),
                    call.lineStart(),
                    call.columnStart(),
                    call.lineEnd(),
                    call.columnEnd(),
                    false
            );
            callProducerStepsByResultNode
                    .computeIfAbsent(call.resultValueId(), key -> new ArrayList<>())
                    .add(step);
            stepByEvidenceId.put(evidenceId, step);
        }
    }

    // 作用：从全限定签名提取方法名；逻辑：截取 # 之后并去掉参数部分。
    private String methodName(String targetQname) {
        String target = targetQname == null ? "" : targetQname;
        int hash = target.lastIndexOf('#');
        if (hash >= 0) {
            target = target.substring(hash + 1);
        }
        int open = target.indexOf('(');
        if (open >= 0) {
            target = target.substring(0, open);
        }
        int dot = target.lastIndexOf('.');
        return dot < 0 ? target : target.substring(dot + 1);
    }

    /**
     * Identifies fields whose stored value is itself an object. A JavaBean
     * setter such as {@code holder.setOrderRequest(request)} stores the object
     * reference, so a later getter exposes a value whose fields are a new
     * path — not {@code holder.orderRequest.channel} continuing the storage
     * path. This distinction lets field paths cross object-valued properties
     * without inventing a field on the holder.
     */
    // 作用：标记对象值的字段；逻辑：字段写入来源为对象载体（或其本身为对象值字段）时递归标记，直到稳定。
    private void buildObjectValuedFields() {
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 32) {
            changed = false;
            for (ValueNodeRecord node : nodesById.values()) {
                if (isEmpty(node.accessPath()) || objectValuedFieldIds.contains(node.valueId())) {
                    continue;
                }
                boolean objectValued = incomingByNode
                        .getOrDefault(node.valueId(), List.of())
                        .stream()
                        .anyMatch(step -> carriesObjectValue(step.source())
                                && ("FIELD_WRITE".equals(step.edgeType())
                                || "FIELD_READ".equals(step.edgeType())
                                || "ARGUMENT_TO".equals(step.edgeType())
                                || "RETURN_FROM".equals(step.edgeType())
                                || "ASSIGNED_FROM".equals(step.edgeType())));

                if (objectValued) {
                    objectValuedFieldIds.add(node.valueId());
                    objectValuedFieldPaths.add(fieldNodeKey(
                            node.scopeSymbolId(), node.rootName(), node.accessPath()));
                    changed = true;
                }
            }
        }
    }

    // 作用：判断来源引用是否携带对象引用；逻辑：空路径的对象载体为真，字段来源则递归查其是否已被标记为对象值字段。
    private boolean carriesObjectValue(ValueRef ref) {
        ValueNodeRecord source = nodesById.get(ref.nodeId());
        if (source == null) {
            return false;
        }
        if (!isEmpty(source.accessPath())) {
            return objectValuedFieldIds.contains(source.valueId())
                    || (!isEmpty(source.accessPath()) && objectValuedFieldPaths.contains(
                    fieldNodeKey(source.scopeSymbolId(), source.rootName(), source.accessPath())));
        }
        return switch (source.valueKind()) {
            case "PARAMETER", "LOCAL", "EXPRESSION", "CALL_RESULT", "CONSTRUCTOR_RESULT", "FIELD" -> true;
            default -> false;
        };
    }

    // 作用：判断字段是否存储对象引用；逻辑：查对象值字段标记集合。
    private boolean isObjectValuedField(ValueNodeRecord field) {
        if (field == null) {
            return false;
        }
        return objectValuedFieldIds.contains(field.valueId())
                || (!isEmpty(field.accessPath()) && objectValuedFieldPaths.contains(
                fieldNodeKey(field.scopeSymbolId(), field.rootName(), field.accessPath())));
    }

    /**
     * A parameter that stores an object-valued field in the caller carries the
     * same field semantics into the callee. Propagate the marked field path
     * along ARGUMENT_TO and RETURN_FROM bridges so a getter in another method
     * is still treated as an object hand-off.
     */
    // 作用：跨调用边界传播对象值字段路径；逻辑：沿对象的实参与返回值桥接，把相同的访问路径标记到目标对象。
    private void propagateObjectValuedFieldsAcrossBridges() {
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 32) {
            changed = false;
            for (String key : List.copyOf(objectValuedFieldPaths)) {
                String[] parts = key.split("\u0002", -1);
                if (parts.length < 3) {
                    continue;
                }
                String scope = parts[0];
                String rootName = parts[1];
                List<String> path = List.of(parts[2].split("\u0001", -1));
                for (ValueNodeRecord root : objectNodesByScopeAndName.getOrDefault(
                        scopeAndValue(scope, rootName), List.of())) {
                    for (Step step : outgoingByNode.getOrDefault(root.valueId(), List.of())) {
                        if (!"ARGUMENT_TO".equals(step.edgeType())
                                && !"RETURN_FROM".equals(step.edgeType())) {
                            continue;
                        }
                        ValueNodeRecord target = nodesById.get(step.target().nodeId());
                        if (target == null || !isEmpty(target.accessPath())) {
                            continue;
                        }
                        String targetKey = fieldNodeKey(
                                target.scopeSymbolId(), target.canonicalValue(), path);
                        if (objectValuedFieldPaths.add(targetKey)) {
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    /**
     * Object-valued getters hand the stored object to a local or field. Make
     * that receiver expose its own field expansions in the index, otherwise a
     * queried object field can reach the receiver yet still be reported as a
     * terminal {@code NO_USE} node by the boundary projection.
     */
    // 作用：为对象值 getter 的接收者登记字段展开出边；逻辑：按读取边目标根节点补入展开步并去重。
    private void buildObjectFieldReadExpansions() {
        Set<String> added = new HashSet<>();
        for (ValueNodeRecord field : nodesById.values()) {
            if (!isObjectValuedField(field)) {
                continue;
            }
            for (Step read : outgoingByNode.getOrDefault(field.valueId(), List.of())) {
                if (!"FIELD_READ".equals(read.edgeType())) {
                    continue;
                }
                ValueNodeRecord receiver = nodesById.get(read.target().nodeId());
                if (receiver == null || !isEmpty(receiver.accessPath())) {
                    continue;
                }
                for (FieldExpansion expansion : fieldExpansionsByNode.getOrDefault(
                        receiver.valueId(), List.of())) {
                    String key = receiver.valueId() + "\u0001" + String.join(".", expansion.path());
                    if (!added.add(key)) {
                        continue;
                    }
                    outgoingByNode
                            .computeIfAbsent(receiver.valueId(), ignored -> new ArrayList<>())
                            .add(expansion.emptyStep());
                    stepByEvidenceId.put(expansion.emptyStep().evidenceId(), expansion.emptyStep());
                }
            }
        }
    }

    // 作用：建立字段展开索引；逻辑：从字段节点与数据边推导可展开的字段路径。
    private void buildFieldExpansions() {
        for (ValueNodeRecord field : graph.valueNodes()) {
            if (isEmpty(field.accessPath()) || field.rootName() == null) {
                continue;
            }
            // Instance fields are concrete writes, not children of a virtual
            // object root. Expanding every this.* field would mix constructor
            // writes and same-named parameters across scopes. A direct query
            // for this.orderId is still supported by canonicalFieldRef().
            if ("this".equals(field.rootName())) {
                continue;
            }
            // Branch/PHI nodes are internal version carriers. They may bridge
            // concrete writes during cross-call binding, but must not be
            // exposed as additional object fields during virtual expansion.
            if (isLogicalVersionNode(field)) {
                continue;
            }
            // A getter used only as a logging argument has no lineage edge and
            // must not become a selectable field of the whole object.
            if (!fieldNodeIdsWithEvidence.contains(field.valueId())) {
                continue;
            }
            List<ValueNodeRecord> roots = objectNodesByScopeAndName.getOrDefault(
                    scopeAndValue(field.scopeSymbolId(), field.rootName()),
                    List.of()
            );
            List<ValueNodeRecord> visibleRoots = roots.stream()
                    .filter(root -> root.declarationOffset() <= field.declarationOffset())
                    .toList();
            if (visibleRoots.isEmpty()) {
                continue;
            }
            ValueNodeRecord root = visibleRoots.stream()
                    .max(Comparator.comparingInt(ValueNodeRecord::declarationOffset)
                            .thenComparingInt(ValueNodeRecord::definitionOrdinal))
                    .orElse(null);
            Step emptyStep = expansionStep(root, field, List.of(), "field-expand");
            Step pathStep = expansionStep(root, field, field.accessPath(), "field-select");
            fieldExpansionsByNode
                    .computeIfAbsent(root.valueId(), key -> new ArrayList<>())
                    .add(new FieldExpansion(field.accessPath(), emptyStep, pathStep));
            stepByEvidenceId.put(emptyStep.evidenceId(), emptyStep);
            stepByEvidenceId.put(pathStep.evidenceId(), pathStep);
        }
    }

    // 作用：判断是否为逻辑版本节点；逻辑：节点值带版本标记时返回 true。
    private boolean isLogicalVersionNode(ValueNodeRecord node) {
        String canonical = node.canonicalValue();
        return canonical.startsWith("branch:")
                || canonical.startsWith("phi:")
                || canonical.startsWith("before:");
    }

    // 作用：构造字段展开步；逻辑：用根、字段与后缀生成带证据的遍历步。
    private Step expansionStep(ValueNodeRecord root, ValueNodeRecord field, List<String> sourceFields, String suffix) {
        String evidenceId = io.fieldtracegraph.core.support.Ids.sha256(
                root.valueId(),
                field.valueId(),
                suffix,
                String.join(".", sourceFields)
        );
        return new Step(
                new ValueRef(root.valueId(), sourceFields),
                new ValueRef(field.valueId(), List.of()),
                "FIELD_READ",
                "field expansion",
                "EXACT_STATIC",
                evidenceId,
                field.fileId(),
                field.lineStart(),
                field.columnStart(),
                field.lineEnd(),
                field.columnEnd(),
                false
        );
    }

    // 作用：拼接作用域与值；逻辑：用分隔符连接，空值直接返回原串。
    private String scopeAndValue(String scopeId, String value) {
        return scopeId + "\u001f" + Objects.toString(value, "");
    }

    // 作用：判断列表是否为空；逻辑：null 或没有元素时返回 true。
    private static boolean isEmpty(List<String> values) {
        return values == null || values.isEmpty();
    }

    // 作用：判断字段能否沿一条边传播；逻辑：比较边的字段选择、目标节点类型与路径约束。
    private static boolean canPropagateFields(
            String edgeType,
            ValueNodeRecord source,
            ValueNodeRecord target
    ) {
        // A container root is not itself an element object. Propagating a field
        // across root -> element would invent paths such as {@code list.name}.
        if ("CONTAINER_FLOW".equals(edgeType)) {
            return false;
        }
        if ("BUILDER_RESULT".equals(edgeType)) {
            return false;
        }
        if ("CONTAINER_ELEMENT".equals(source.valueKind())
                || "CONTAINER_ELEMENT".equals(target.valueKind())) {
            return false;
        }
        if ("CONSTANT".equals(source.valueKind())) {
            return false;
        }
        // A call result is derived from its arguments only at value level. The
        // same-named field of an argument is not the source of the result
        // field; that link is carried by the explicit RETURN_FROM bridge. Allowing
        // it here invented paths such as {@code pin.addressId} for a String
        // parameter of a DAO lookup.
        if ("CALL_RESULT".equals(target.valueKind())) {
            return false;
        }
        // Indexes, literals and other scalar expressions do not own the field
        // selected from the destination object.
        return !"CONSTANT".equals(target.valueKind());
    }

    // 作用：取节点的入边步；逻辑：查入边索引，必要时补全具体字段入边。
    List<Step> incoming(ValueRef ref) {
        List<Step> result = new ArrayList<>();
        collectIncoming(canonicalFieldRef(ref), result, new HashSet<>());
        return result;
    }

    /**
     * A concrete {@code obj.field} node and the pair
     * {@code (obj root, [field])} are two representations of the same value.
     * Normalising field nodes to the root form lets a field traversal continue
     * through the object-level producer chain (call result, return, PHI) while
     * keeping one stable node identity in the rendered graph.
     */
    // 作用：规范化字段引用；逻辑：把引用转换为字段根加路径的形式。
    private ValueRef canonicalFieldRef(ValueRef ref) {
        ValueNodeRecord node = nodesById.get(ref.nodeId());
        if (node == null || isEmpty(node.accessPath()) || isLogicalVersionNode(node)) {
            return ref;
        }
        // Keep an explicitly selected instance field concrete. There is no
        // synthetic this root to normalize it to, and doing so would expose
        // every field written by constructors as one expanded object.
        if ("this".equals(node.rootName())) {
            return ref;
        }
        ValueNodeRecord root = fieldRoot(node);
        if (root == null) {
            return ref;
        }
        List<String> path = new ArrayList<>(node.accessPath());
        path.addAll(ref.fields());
        return new ValueRef(root.valueId(), List.copyOf(path), ref.callContext());
    }

    // 作用：查找字段所属的根节点；逻辑：沿字段节点的来源回溯到对象根。
    private ValueNodeRecord fieldRoot(ValueNodeRecord field) {
        if (field.rootName() == null || field.accessPath() == null || field.accessPath().isEmpty()) {
            return null;
        }
        ValueNodeRecord candidate = null;
        for (ValueNodeRecord root : objectNodesByScopeAndName.getOrDefault(
                scopeAndValue(field.scopeSymbolId(), field.rootName()),
                List.of()
        )) {
            if (!field.rootName().equals(root.canonicalValue())
                    || root.declarationOffset() > field.declarationOffset()) {
                continue;
            }
            if (candidate == null
                    || root.declarationOffset() > candidate.declarationOffset()
                    || (root.declarationOffset() == candidate.declarationOffset()
                    && root.definitionOrdinal() > candidate.definitionOrdinal())) {
                candidate = root;
            }
        }
        return candidate;
    }

    // 作用：收集节点的入边步；逻辑：合并直接边、字段展开与容器投影结果。
    private void collectIncoming(
            ValueRef ref,
            List<Step> result,
            Set<ValueRef> visiting
    ) {
        if (!visiting.add(ref)) {
            return;
        }
        for (Step step : incomingByNode.getOrDefault(ref.nodeId(), List.of())) {
            ValueRef previous = transitionIncoming(step, ref);
            if (previous == null) {
                continue;
            }
            if (step.propagateFields()) {
                if (!canPropagateSelectedField(step, ref.fields())) {
                    continue;
                }
                result.add(step.withSource(withFields(previous, ref.fields())));
            } else if (canComposeFieldPath(step, ref)) {
                result.add(step.withSource(composeFieldPath(previous, ref.fields())));
            } else if (step.target().fields().equals(ref.fields())) {
                ValueNodeRecord previousNode = nodesById.get(previous.nodeId());
                boolean pathAlreadyEncoded = previousNode != null
                        && !isEmpty(previousNode.accessPath())
                        && previousNode.accessPath().equals(ref.fields());
                result.add(step.withSource(pathAlreadyEncoded
                        ? previous
                        : withFields(previous, ref.fields())));
            }
        }
        if (!ref.fields().isEmpty()) {
            for (Step producer : callProducerStepsByResultNode.getOrDefault(ref.nodeId(), List.of())) {
                result.add(producer.withTarget(new ValueRef(
                        ref.nodeId(),
                        ref.fields(),
                        ref.callContext()
                )));
            }
        }
        // A virtual field path over an object root also needs the concrete
        // field node's incoming steps. Do not project that field node back to
        // its root again: that would immediately re-enter this branch.
        if (ref.fields().isEmpty()) {
            // Tracing an object root backwards must also expose the writes that
            // populated each of its fields, otherwise a value assembled through
            // setters in a callee would stop at "new X()" and never reach the
            // setter arguments.
            for (FieldExpansion expansion : fieldExpansionsByNode.getOrDefault(ref.nodeId(), List.of())) {
                result.add(expansion.emptyStep().reversed());
                collectIncoming(
                        new ValueRef(
                                expansion.emptyStep().target().nodeId(),
                                List.of(),
                                ref.callContext()
                        ),
                        result,
                        visiting
                );
            }
        } else {
            for (FieldExpansion expansion : fieldExpansionsByNode.getOrDefault(ref.nodeId(), List.of())) {
                if (ref.fields().equals(expansion.path())) {
                    ValueNodeRecord field = nodesById.get(expansion.pathStep().target().nodeId());
                    if (field != null) {
                        collectConcreteFieldIncoming(
                                field,
                                ref.fields(),
                                ref.callContext(),
                                result,
                                visiting
                        );
                    }
                } else if (ref.fields().size() > expansion.path().size()
                        && ref.fields().subList(0, expansion.path().size()).equals(expansion.path())
                        && isObjectValuedField(nodesById.get(expansion.pathStep().target().nodeId()))) {
                    collectObjectValuedFieldIncoming(expansion, ref, result, visiting);
                }
            }
        }
        visiting.remove(ref);
    }

    /**
     * Preserve the selected field while moving from a concrete field node to
     * the object carrier that owns it. Without this projection a mutation such
     * as {@code tabs[*].defaultOn} would jump back to bare {@code tabs}, then
     * follow the list factory and every unrelated branch that produced the
     * list.
     */
    // 作用：收集具体字段的入边；逻辑：按字段路径匹配来源边并保留命中项。
    private void collectConcreteFieldIncoming(
            ValueNodeRecord field,
            List<String> fields,
            String callContext,
            List<Step> result,
            Set<ValueRef> visiting
    ) {
        ValueRef fieldRef = new ValueRef(field.valueId(), fields, callContext);
        if (!visiting.add(fieldRef)) {
            return;
        }
        for (Step step : incomingByNode.getOrDefault(field.valueId(), List.of())) {
            ValueRef previous = transitionIncoming(step, fieldRef);
            if (previous == null) {
                continue;
            }
            if ("MUTATES_PARAMETER".equals(step.edgeType())) {
                result.add(step.withSource(withFields(previous, fields)));
            } else {
                result.add(step.withSource(previous));
            }
        }
        visiting.remove(fieldRef);
    }

    /**
     * A deep path such as {@code holder.orderRequest.channel} crosses a field
     * whose value is an object. The storage field keeps only its own segment;
     * the remaining suffix belongs to the object that was written into it, so
     * the producer of {@code orderRequest} receives the suffix while the
     * holder field segment is consumed.
     */
    // 作用：跨对象值字段回溯；逻辑：把深层字段路径去掉存储字段段后，投影到该字段的对象来源上。
    private void collectObjectValuedFieldIncoming(
            FieldExpansion expansion,
            ValueRef ref,
            List<Step> result,
            Set<ValueRef> visiting
    ) {
        ValueNodeRecord field = nodesById.get(expansion.pathStep().target().nodeId());
        if (field == null) {
            return;
        }
        List<String> rest = List.copyOf(
                ref.fields().subList(expansion.path().size(), ref.fields().size())
        );
        ValueRef fieldRef = new ValueRef(field.valueId(), rest, ref.callContext());
        if (!visiting.add(fieldRef)) {
            return;
        }
        for (Step step : incomingByNode.getOrDefault(field.valueId(), List.of())) {
            ValueRef previous = transitionIncoming(step, fieldRef);
            if (previous == null) {
                continue;
            }
            ValueNodeRecord source = nodesById.get(previous.nodeId());
            if ("FIELD_WRITE".equals(step.edgeType())
                    && source != null
                    && isEmpty(source.accessPath())) {
                result.add(step.withSource(new ValueRef(
                        previous.nodeId(), rest, previous.callContext()
                )));
                continue;
            }
            if (source != null && !isEmpty(source.accessPath())) {
                result.add(step.withSource(composeFieldPath(previous, rest)));
                continue;
            }
            result.add(step.withSource(new ValueRef(
                    previous.nodeId(), rest, previous.callContext()
            )));
        }
        visiting.remove(fieldRef);
    }

    // 作用：取节点的出边步；逻辑：合并直接边、容器展开与字段传播结果。
    List<Step> outgoing(ValueRef ref) {
        List<Step> result = new ArrayList<>();
        ValueRef current = canonicalFieldRef(ref);
        List<Step> candidateSteps = new ArrayList<>(
                outgoingByNode.getOrDefault(current.nodeId(), List.of())
        );
        candidateSteps.addAll(containerRootOutgoing(current));
        for (Step step : candidateSteps) {
            ValueRef next = transitionOutgoing(step, current);
            if (next == null) {
                continue;
            }
            ValueRef projectedContainerField = projectContainerFieldWrite(step, current, next);
            if (projectedContainerField != null) {
                result.add(step.withSource(current).withTarget(projectedContainerField));
                continue;
            }
            ValueNodeRecord nextNode = nodesById.get(next.nodeId());
            if (!current.fields().isEmpty()
                    && "FIELD_READ".equals(step.edgeType())
                    && "field expansion".equals(step.operation())
                    && nextNode != null
                    && !isEmpty(nextNode.accessPath())
                    && current.fields().equals(nextNode.accessPath())) {
                /*
                 * The selected field already represents this expansion; its
                 * concrete outgoing evidence is handled below by
                 * addConcreteFieldOutgoing. Emitting the expansion again here
                 * would create a field-to-itself self loop.
                 */
                continue;
            }
            ValueRef selectedFieldTarget = selectedFieldTarget(step, current, next);
            if (selectedFieldTarget != null) {
                result.add(step.withSource(current).withTarget(selectedFieldTarget));
                continue;
            }
            if (step.propagateFields()) {
                if (!canPropagateSelectedField(step, next, current.fields())) {
                    continue;
                }
                result.add(step.withTarget(new ValueRef(
                        next.nodeId(), current.fields(), next.callContext()
                )));
            } else if (step.source().fields().equals(current.fields())) {
                result.add(step.withTarget(next));
            }
        }
        for (Step step : nestedContainerOutgoing(current)) {
            ValueRef next = transitionOutgoing(step, current);
            if (next == null) {
                continue;
            }
            ValueRef projected = projectNestedContainerWrite(step, current, next);
            if (projected != null) {
                result.add(step.withSource(current).withTarget(projected));
            }
        }
        for (FieldExpansion expansion : fieldExpansionsByNode.getOrDefault(current.nodeId(), List.of())) {
            if (current.fields().isEmpty()) {
                if (!expansionRootIds.contains(current.nodeId())) {
                    continue;
                }
                result.add(expansion.emptyStep());
            } else if (current.fields().equals(expansion.path())) {
                addConcreteFieldOutgoing(current, expansion, result);
            }
        }
        return result;
    }

    /**
     * The selected field root is often {@code list[*]}, while a later
     * whole-container hand-off is recorded on {@code list}. Both represent
     * the same carrier; include the root's outgoing writes so
     * {@code map.put("key", list)} preserves the selected nested field.
     */
    // 作用：计算容器根的出边；逻辑：处理容器整体写入与元素读取。
    private List<Step> containerRootOutgoing(ValueRef current) {
        if (current.fields().isEmpty()) {
            return List.of();
        }
        ValueNodeRecord element = nodesById.get(current.nodeId());
        if (element == null || !element.canonicalValue().endsWith("[*]")) {
            return List.of();
        }
        String rootValue = element.canonicalValue().substring(
                0,
                element.canonicalValue().length() - 3
        );
        ValueNodeRecord root = nodesById.values().stream()
                .filter(candidate -> candidate.scopeSymbolId().equals(element.scopeSymbolId()))
                .filter(candidate -> rootValue.equals(candidate.canonicalValue()))
                .filter(candidate -> isEmpty(candidate.accessPath()))
                .filter(candidate -> candidate.declarationOffset() <= element.declarationOffset())
                .max(Comparator.comparingInt(ValueNodeRecord::declarationOffset)
                        .thenComparingInt(ValueNodeRecord::definitionOrdinal))
                .orElse(null);
        return root == null
                ? List.of()
                : outgoingByNode.getOrDefault(root.valueId(), List.of());
    }

    /**
     * A selected map entry is nested inside its owning map. When that owning
     * map is later stored in another container, the selected entry keeps its
     * relative key, for example:
     *
     * <pre>
     * outer["branch"] = inner;
     * result["floor"] = outer;
     * // selected inner["tabs"] becomes result["floor"]["branch"]["tabs"]
     * </pre>
     *
     * Only container write steps are projected. Treating every operation on
     * the owning map as field lineage would mix lookup keys and unrelated
     * conditions into value lineage.
     */
    // 作用：计算嵌套容器的出边；逻辑：逐层展开内部元素并生成对应步。
    private List<Step> nestedContainerOutgoing(ValueRef current) {
        NestedContainer carrier = nestedContainer(current);
        return carrier == null
                ? List.of()
                : outgoingByNode.getOrDefault(carrier.root().valueId(), List.of());
    }

    // 作用：投影嵌套容器写入；逻辑：把外层写入映射到内层元素引用。
    private ValueRef projectNestedContainerWrite(Step step, ValueRef current, ValueRef next) {
        if (!"WRITES_ELEMENT".equals(step.edgeType())
                && !"COPIES_ELEMENTS".equals(step.edgeType())) {
            return null;
        }
        ValueNodeRecord target = nodesById.get(next.nodeId());
        if (target == null || !"CONTAINER_ELEMENT".equals(target.valueKind())) {
            return null;
        }
        NestedContainer carrier = nestedContainer(current);
        if (carrier == null) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        fields.add(carrier.relativePath());
        fields.addAll(current.fields());
        return new ValueRef(next.nodeId(), List.copyOf(fields), next.callContext());
    }

    // 作用：解析嵌套容器结构；逻辑：从节点值提取容器层级与元素路径。
    private NestedContainer nestedContainer(ValueRef current) {
        ValueNodeRecord entry = nodesById.get(current.nodeId());
        if (entry == null
                || !"CONTAINER_ELEMENT".equals(entry.valueKind())
                || entry.canonicalValue() == null) {
            return null;
        }
        String canonical = entry.canonicalValue();
        if (canonical.endsWith("[*]")) {
            return null;
        }
        int bracket = canonical.lastIndexOf('[');
        if (bracket <= 0 || !canonical.endsWith("]")) {
            return null;
        }
        String relativePath = canonical.substring(bracket);
        if (relativePath.contains("[") && !relativePath.matches("\\[[^\\[\\]]+\\]")) {
            return null;
        }
        String rootValue = canonical.substring(0, bracket);
        ValueNodeRecord root = nodesById.values().stream()
                .filter(candidate -> candidate.scopeSymbolId().equals(entry.scopeSymbolId()))
                .filter(candidate -> rootValue.equals(candidate.canonicalValue()))
                .filter(candidate -> isEmpty(candidate.accessPath()))
                .filter(candidate -> candidate.declarationOffset() <= entry.declarationOffset())
                .max(Comparator.comparingInt(ValueNodeRecord::declarationOffset)
                        .thenComparingInt(ValueNodeRecord::definitionOrdinal))
                .orElse(null);
        return root == null ? null : new NestedContainer(root, relativePath);
    }

    /**
     * A whole collection written into a map/list keeps each element's field:
     * {@code map.put("tabs", tabs)} therefore carries
     * {@code tabs[*].defaultOn} into
     * {@code map["tabs"][*].defaultOn}. The persisted edge is container-level,
     * but the field selected by the user is unchanged by that copy.
     */
    // 作用：投影容器字段写入；逻辑：把容器元素字段映射到目标引用。
    private ValueRef projectContainerFieldWrite(Step step, ValueRef current, ValueRef next) {
        if (current.fields().isEmpty()
                || (!"WRITES_ELEMENT".equals(step.edgeType())
                && !"COPIES_ELEMENTS".equals(step.edgeType()))) {
            return null;
        }
        ValueNodeRecord target = nodesById.get(next.nodeId());
        if (target == null || !"CONTAINER_ELEMENT".equals(target.valueKind())) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        ValueNodeRecord source = nodesById.get(current.nodeId());
        boolean mapEntryStoresCollection = source != null
                && isCollectionCarrier(source)
                && target.canonicalValue().matches(".*\\[\"[^\"]+\"\\]$");
        if (mapEntryStoresCollection && !current.fields().get(0).startsWith("[")) {
            fields.add("[*]");
        }
        fields.addAll(current.fields());
        return new ValueRef(next.nodeId(), List.copyOf(fields), next.callContext());
    }

    // 作用：判断节点是否为集合载体；逻辑：节点类型属于集合或映射时返回 true。
    private boolean isCollectionCarrier(ValueNodeRecord source) {
        return source.canonicalValue().endsWith("[*]")
                || containerElementKeys.contains(
                scopeAndValue(source.scopeSymbolId(), source.canonicalValue() + "[*]")
        );
    }

    // 作用：判断选中字段能否沿步传播；逻辑：检查步的字段选择与目标路径是否匹配。
    private boolean canPropagateSelectedField(Step step, List<String> fields) {
        return canPropagateSelectedField(step, step.target(), fields);
    }

    // 作用：结合目标引用判断选中字段能否传播；逻辑：比较目标路径与步上的字段选择。
    private boolean canPropagateSelectedField(Step step, ValueRef target, List<String> fields) {
        if (fields.isEmpty()) {
            return true;
        }
        if ("ARGUMENT_TO".equals(step.edgeType())) {
            return supportsFieldPath(target.nodeId(), fields);
        }
        if ("RETURN_FROM".equals(step.edgeType())) {
            return supportsReturnFieldPath(step.source().nodeId(), fields);
        }
        if ("ASSIGNED_FROM".equals(step.edgeType())
                || "COPIES_ELEMENTS".equals(step.edgeType())) {
            /*
             * Object-level assignment does not imply field-level production.
             * For example, {@code tabs = convert(...)} only means the local
             * receives an object; it must not make every field later mutated
             * on {@code tabs} look like it came from {@code convert(...)}.
             * A concrete field path may cross this edge only when either side
             * has concrete evidence for that path. A call result can provide
             * that evidence through its callee return object, which is how
             * {@code orderRequest = querySignInfo(...)} continues from a
             * caller local back into the returned object fields.
             */
            return supportsFieldPath(step.source().nodeId(), fields)
                    || supportsDirectFieldPath(step.target().nodeId(), fields);
        }
        return true;
    }

    // 作用：判断节点是否支持直接字段路径；逻辑：查字段节点键集合，命中即支持。
    private boolean supportsDirectFieldPath(String nodeId, List<String> fields) {
        return fieldExpansionsByNode.getOrDefault(nodeId, List.of()).stream()
                .anyMatch(expansion -> fields.equals(expansion.path()));
    }

    /**
     * A return bridge may only carry a selected field when the callee's return
     * object actually received that field. Without this guard any selected
     * suffix would be projected through every call result, inventing fields
     * that the callee never wrote.
     */
    // 作用：判断返回节点是否支持字段路径；逻辑：检查返回值来源字段是否覆盖目标路径。
    private boolean supportsReturnFieldPath(String returnNodeId, List<String> fields) {
        if (supportsFieldPath(returnNodeId, fields)) {
            return true;
        }
        for (ValueNodeRecord source : dataSourcesByDestination.getOrDefault(returnNodeId, List.of())) {
            if (supportsFieldPath(source.valueId(), fields)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Once a query selects {@code object.field}, the concrete field node keeps
     * the evidence edges that leave the object root. Project those edges back
     * onto the selected path so value-preserving calls such as
     * {@code Long.parseLong(agreement.getAddressId())} can continue into the
     * field that receives their result.
     */
    // 作用：补充具体字段的出边；逻辑：按字段路径生成下游步并写入出边索引。
    private void addConcreteFieldOutgoing(
            ValueRef selected,
            FieldExpansion expansion,
            List<Step> result
    ) {
        ValueNodeRecord primary = nodesById.get(expansion.pathStep().target().nodeId());
        if (primary == null) {
            return;
        }
        /*
         * A field can have several SSA versions in one scope: the write in one
         * statement and the later read in another are distinct nodes with the
         * same root and access path. A query must follow the outgoing evidence
         * of every version, not only the first one the expansion selected.
         */
        List<ValueNodeRecord> versions = fieldNodesByScopeAndPath.getOrDefault(
                fieldNodeKey(primary.scopeSymbolId(), primary.rootName(), primary.accessPath()),
                List.of(primary)
        );
        boolean objectValued = versions.stream().anyMatch(this::isObjectValuedField);
        for (ValueNodeRecord field : versions) {
            addConcreteFieldVersionOutgoing(selected, field, objectValued, result);
        }
    }

    // 作用：处理单个字段版本的下游边；逻辑：对象值 getter 单独投影，其余按目标节点形态转交。
    private void addConcreteFieldVersionOutgoing(
            ValueRef selected,
            ValueNodeRecord field,
            boolean objectValued,
            List<Step> result
    ) {
        ValueRef concrete = new ValueRef(field.valueId(), List.of(), selected.callContext());
        for (Step step : outgoingByNode.getOrDefault(field.valueId(), List.of())) {
            ValueRef next = transitionOutgoing(step, concrete);
            if (next == null) {
                continue;
            }
            if (objectValued
                    && "FIELD_READ".equals(step.edgeType())
                    && next.fields().isEmpty()) {
                /*
                 * A getter of an object-valued property returns the stored
                 * object, not a deeper property path. Continue from the
                 * receiver object and expose the fields that are actually read
                 * from it, so setXxx(...) -> getXxx() does not dead-end.
                 */
                result.add(step.withSource(selected).withTarget(
                        new ValueRef(next.nodeId(), List.of(), next.callContext())
                ));
                continue;
            }
            ValueRef target = selectedFieldTarget(step, selected, next);
            if (target != null) {
                result.add(step.withSource(selected).withTarget(target));
                continue;
            }
            ValueNodeRecord targetNode = nodesById.get(next.nodeId());
            if ("SQL_PARAMETER".equals(step.edgeType())
                    || (targetNode != null && "CONTAINER_ELEMENT".equals(targetNode.valueKind()))
                    || (targetNode != null && !isEmpty(targetNode.accessPath()))) {
                // A concrete field may flow into a differently named field,
                // a container element, or a SQL bind. Keep the explicit
                // target instead of appending the source field path.
                result.add(step.withSource(selected).withTarget(
                        new ValueRef(next.nodeId(), List.of(), next.callContext())
                ));
                continue;
            }
            boolean preserves = preservesSelectedField(step)
                    && targetNode != null
                    && "CALL_RESULT".equals(targetNode.valueKind())
                    && !selected.fields().isEmpty()
                    && supportsFieldPath(targetNode.valueId(), selected.fields());
            if (preserves) {
                result.add(step.withSource(selected).withTarget(new ValueRef(
                        next.nodeId(),
                        selected.fields(),
                        next.callContext()
                )));
            } else if ("FIELD_READ".equals(step.edgeType())
                    && targetNode != null
                    && isEmpty(targetNode.accessPath())) {
                /*
                 * The read value becomes the target object/scalar itself. Do
                 * not append the selected field name: for
                 * {@code String value = object.getStatus()} the result is
                 * {@code value}, not {@code value.status}.
                 */
                result.add(step.withSource(selected).withTarget(
                        new ValueRef(next.nodeId(), List.of(), next.callContext())
                ));
            }
        }
    }

    /**
     * A write to a concrete field with the same access path is the sink of a
     * selected field path. Returning the concrete target node avoids rendering
     * a synthetic {@code object.field.field} path.
     */
    // 作用：计算选中字段的目标引用；逻辑：根据步与下一节点合成带字段的引用。
    private ValueRef selectedFieldTarget(Step step, ValueRef selected, ValueRef next) {
        if (selected.fields().isEmpty()) {
            return null;
        }
        ValueNodeRecord target = nodesById.get(next.nodeId());
        if (target == null || isEmpty(target.accessPath())
                || !selected.fields().equals(target.accessPath())) {
            return null;
        }
        return new ValueRef(next.nodeId(), List.of(), next.callContext());
    }

    // 作用：判断节点是否支持字段路径；逻辑：转调带访问集合的重载方法。
    private boolean supportsFieldPath(String nodeId, List<String> fields) {
        return supportsFieldPath(nodeId, fields, new HashSet<>());
    }

    /**
     * A parameter can carry a field into another callee even when that method
     * does not read the field directly. For example, autoPay forwards
     * orderRequest through executeSubmitOrder before buildInvoice reads
     * addressId. Checking only the first callee's local field expansions stops
     * every such forward chain at the method boundary.
     */
    // 作用：递归判断字段路径是否支持；逻辑：沿来源边回溯匹配路径，访问集合用于防止环。
    private boolean supportsFieldPath(String nodeId, List<String> fields, Set<String> visiting) {
        String key = nodeId + "\u0001" + String.join(".", fields);
        if (supportedFieldPaths.contains(key)) {
            return true;
        }
        if (!visiting.add(key)) {
            return false;
        }
        if (fieldExpansionsByNode.getOrDefault(nodeId, List.of()).stream()
                .anyMatch(expansion -> fields.equals(expansion.path()))) {
            supportedFieldPaths.add(key);
            return true;
        }
        ValueNodeRecord node = nodesById.get(nodeId);
        if (node == null) {
            return false;
        }
        if ("CALL_RESULT".equals(node.valueKind())
                && callResultCarriesField(nodeId, fields, visiting)) {
            supportedFieldPaths.add(key);
            return true;
        }
        /*
         * A local object that is an unchanged alias of another object carries
         * the same selected field. Follow only object-level ASSIGNED_FROM
         * edges; concrete field edges are handled by fieldExpansionsByNode.
         * This is the bridge from:
         *
         *   merchantConfig = paymentService.fetchMerchantConfig(...)
         *
         * back to the external call-result boundary without treating pin or
         * orderId query keys as sources of the field.
         */
        for (Step step : incomingByNode.getOrDefault(nodeId, List.of())) {
            if (!"ASSIGNED_FROM".equals(step.edgeType()) || !step.propagateFields()) {
                continue;
            }
            ValueNodeRecord source = nodesById.get(step.source().nodeId());
            if (source == null || !isEmpty(source.accessPath())) {
                continue;
            }
            if (supportsFieldPath(source.valueId(), fields, visiting)) {
                supportedFieldPaths.add(key);
                return true;
            }
        }
        for (ValueNodeRecord calleeParameter : callParametersByArgumentNode.getOrDefault(nodeId, List.of())) {
            if (supportsFieldPath(calleeParameter.valueId(), fields, visiting)) {
                supportedFieldPaths.add(key);
                return true;
            }
        }
        return false;
    }

    /**
     * A call result owns a selected field only when the resolved callee has a
     * return object with evidence for that exact path. This keeps arbitrary
     * suffixes from being manufactured through external or unresolved calls.
     */
    // 作用：判断调用结果是否携带字段；逻辑：检查返回节点与被调方的字段证据。
    private boolean callResultCarriesField(
            String resultNodeId,
            List<String> fields,
            Set<String> visiting
    ) {
        boolean resolvedCalleeObserved = false;
        for (CallEdgeRecord call : callsByResultNode.getOrDefault(resultNodeId, List.of())) {
            resolvedCalleeObserved = true;
            for (ValueNodeRecord returnNode : returnsByScope.getOrDefault(call.calleeSymbolId(), List.of())) {
                if (supportsReturnFieldPath(returnNode.valueId(), fields)) {
                    return true;
                }
                String key = returnNode.valueId() + "\u0001" + String.join(".", fields);
                if (visiting.add(key)
                        && supportsFieldPath(returnNode.valueId(), fields, visiting)) {
                    return true;
                }
            }
        }
        /*
         * An external method result has no local callee RETURN to inspect.
         * The selected field is still a real field because it was resolved
         * from a concrete read in the caller. Keep the result as an explicit
         * boundary rather than dropping the chain or inventing a local source.
         */
        return !resolvedCalleeObserved && externalCallResultNodeIds.contains(resultNodeId);
    }

    // 作用：按位置取形参节点；逻辑：从作用域参数列表读取，越界返回 null。
    private ValueNodeRecord parameterAt(String scopeId, int index) {
        List<ValueNodeRecord> parameters = parametersByScope.getOrDefault(scopeId, List.of());
        return index >= 0 && index < parameters.size() ? parameters.get(index) : null;
    }

    /**
     * FIELD_READ from a concrete field is a scalar hand-off. The selected
     * field path must follow a pure conversion call result until the next
     * concrete field write or another call boundary.
     */
    // 作用：判断步是否保留选中字段；逻辑：检查步类型与字段选择标记。
    private boolean preservesSelectedField(Step step) {
        return "FIELD_READ".equals(step.edgeType());
    }

    /**
     * Projects a bridge transition into the caller/callee context carried by
     * the traversal. Crossing a bridge always moves between caller and callee,
     * so the call-site frame is pushed when the traversal is outside it and
     * popped when the traversal is already inside it.
     */
    // 作用：计算入边转换后的引用；逻辑：根据步方向与字段路径生成上游引用。
    private ValueRef transitionIncoming(Step step, ValueRef current) {
        if (step.callContextDirection() == CallContextDirection.NONE) {
            return withContext(step.source(), current.callContext());
        }
        return withContext(step.source(), shiftCallContext(current.callContext(), step.callSiteId()));
    }

    /**
     * A field read is also an object-level hand-off when the target carries a
     * field suffix. Example: {@code holder = source.getData()} followed by
     * {@code holder.name} must expose {@code source.data.name}.
     */
    // 作用：判断能否组合字段路径；逻辑：检查步与当前引用的上下文是否允许拼接。
    private boolean canComposeFieldPath(Step step, ValueRef current) {
        ValueNodeRecord source = nodesById.get(step.source().nodeId());
        return source != null
                && !isEmpty(source.accessPath())
                && !isLogicalVersionNode(source)
                && fieldRoot(source) != null
                && !current.fields().isEmpty()
                && !step.target().fields().equals(current.fields())
                && "FIELD_READ".equals(step.edgeType())
                && !isArgumentOrReturnCarrier(step.target().nodeId());
    }

    // 作用：判断节点是否为实参或返回值载体；逻辑：节点类型属于参数或返回值时返回 true。
    private boolean isArgumentOrReturnCarrier(String nodeId) {
        ValueNodeRecord node = nodesById.get(nodeId);
        return node != null
                && ("CALL_RESULT".equals(node.valueKind()) || "PARAMETER".equals(node.valueKind()));
    }

    // 作用：组合字段路径；逻辑：把来源引用与当前字段列表拼接成新引用。
    private ValueRef composeFieldPath(ValueRef sourceRef, List<String> currentFields) {
        ValueNodeRecord source = nodesById.get(sourceRef.nodeId());
        if (source == null || isEmpty(source.accessPath())) {
            return withFields(sourceRef, currentFields);
        }
        ValueNodeRecord root = fieldRoot(source);
        if (root == null) {
            return withFields(sourceRef, currentFields);
        }
        List<String> path = new ArrayList<>(source.accessPath());
        path.addAll(currentFields);
        return new ValueRef(root.valueId(), List.copyOf(path), sourceRef.callContext());
    }

    // 作用：替换引用的字段路径；逻辑：复制引用并写入新的字段列表。
    private ValueRef withFields(ValueRef ref, List<String> fields) {
        return new ValueRef(ref.nodeId(), fields, ref.callContext());
    }

    // 作用：计算出边转换后的引用；逻辑：根据步与字段选择生成下游引用。
    private ValueRef transitionOutgoing(Step step, ValueRef current) {
        if (step.callContextDirection() == CallContextDirection.NONE) {
            return withContext(step.target(), current.callContext());
        }
        return withContext(step.target(), shiftCallContext(current.callContext(), step.callSiteId()));
    }

    /**
     * A call bridge always moves between exactly two frames. If the traversal
     * already stands inside the call site frame it is leaving that frame,
     * otherwise the query was seeded inside the callee and is entering the
     * caller frame for the first time. Requiring the frame to be present made
     * {@code trace --direction in} from a method parameter return no caller at
     * all, so both cases must move the stack by one.
     */
    // 作用：切换调用上下文；逻辑：根据是否命中调用点更新上下文串。
    private String shiftCallContext(String context, String callSiteId) {
        return hasCallContext(context, callSiteId)
                ? popCallContext(context, callSiteId)
                : pushCallContext(context, callSiteId);
    }

    // 作用：替换引用的调用上下文；逻辑：复制引用并写入新上下文。
    private ValueRef withContext(ValueRef ref, String context) {
        return new ValueRef(ref.nodeId(), ref.fields(), context);
    }

    // 作用：判断上下文是否包含指定调用点；逻辑：检查上下文串中的标记。
    private boolean hasCallContext(String context, String callSiteId) {
        return callSiteId != null
                && !callSiteId.isBlank()
                && (context.equals(callSiteId) || context.endsWith("/" + callSiteId));
    }

    // 作用：压入调用上下文；逻辑：把调用点追加到上下文末尾。
    private String pushCallContext(String context, String callSiteId) {
        if (callSiteId == null || callSiteId.isBlank()) {
            return context;
        }
        return context.isBlank() ? callSiteId : context + "/" + callSiteId;
    }

    // 作用：弹出调用上下文；逻辑：移除最近的调用点标记。
    private String popCallContext(String context, String callSiteId) {
        if (context.equals(callSiteId)) {
            return "";
        }
        return context.substring(0, context.length() - callSiteId.length() - 1);
    }

    // 作用：返回全部值节点；逻辑：从节点表取出值集合。
    List<ValueNodeRecord> valueNodes() {
        return graph.valueNodes();
    }

    // 作用：返回方法候选信息；逻辑：直接暴露构建时保存的方法。
    MethodCandidate method() {
        return graph.method();
    }

    // 作用：按 ID 取节点；逻辑：查节点表，缺失返回 null。
    ValueNodeRecord node(String nodeId) {
        return nodesById.get(nodeId);
    }

    // 作用：判断字段节点是否存在；逻辑：查字段节点键集合。
    boolean hasFieldNode(String scopeId, String rootName, List<String> accessPath) {
        return fieldNodeKeys.contains(fieldNodeKey(scopeId, rootName, accessPath));
    }

    // 作用：生成字段节点键；逻辑：拼接作用域、根名与访问路径。
    private String fieldNodeKey(String scopeId, String rootName, List<String> accessPath) {
        return scopeId + "\u0002" + rootName + "\u0002" + String.join("\u0001", accessPath);
    }

    // 作用：按 ID 取源文件；逻辑：查文件表，缺失返回 null。
    SourceFileRecord file(String fileId) {
        return filesById.get(fileId);
    }

    // 作用：按证据 ID 取遍历步；逻辑：查步索引。
    Step stepByEvidenceId(String evidenceId) {
        return stepByEvidenceId.get(evidenceId);
    }

    // 作用：把引用转成输出节点；逻辑：填充展示名、类型与位置信息。
    TraceNode toTraceNode(ValueRef ref) {
        ValueNodeRecord node = nodesById.get(ref.nodeId());
        String suffix = ValuePath.suffix(ref.fields());
        String displayName = ref.fields().isEmpty()
                ? (node == null ? ref.nodeId() : node.canonicalValue())
                : (node == null
                ? ref.key()
                : node.canonicalValue() + (suffix.startsWith("[") ? "" : ".") + suffix);
        displayName = canonicalFieldDisplay(displayName);
        String kind = node == null
                ? "EXPRESSION"
                : (!ref.fields().isEmpty() && !"FIELD".equals(node.valueKind()) ? "FIELD" : node.valueKind());
        kind = displayKind(kind, displayName);
        String scopeId = node == null ? null : node.scopeSymbolId();
        return new TraceNode(
                ref.key(),
                scopeId,
                scopeId == null ? null : graph.scopeQnames().getOrDefault(scopeId, scopeId),
                displayName,
                kind,
                node == null ? 0 : node.lineStart(),
                isInternalVersion(displayName, kind)
        );
    }

    /**
     * Keeps persisted pre-canonicalization indexes readable after an analyzer
     * upgrade: field segments named {@code isX}/{@code IsX} render as
     * {@code x}, while the root expression is untouched.
     */
    // 作用：规范化字段展示名；逻辑：去掉内部前缀并保留可读路径。
    private static String canonicalFieldDisplay(String displayName) {
        if (displayName == null || displayName.indexOf('.') < 0) {
            return displayName;
        }
        String[] parts = displayName.split("\\.", -1);
        for (int index = 1; index < parts.length; index++) {
            String part = parts[index];
            if (part.length() <= 2 || !part.regionMatches(true, 0, "is", 0, 2)
                    || !Character.isUpperCase(part.charAt(2))) {
                continue;
            }
            String property = part.substring(2);
            parts[index] = Character.toLowerCase(property.charAt(0)) + property.substring(1);
        }
        return String.join(".", parts);
    }

    /**
     * Keeps the persisted kind vocabulary stable while exposing the same
     * distinctions the shared result contract uses.
     */
    // 作用：生成节点类型展示名；逻辑：按类型与字段名归类。
    private String displayKind(String kind, String displayName) {
        if ("CALL_RESULT".equals(kind) && displayName != null && displayName.startsWith("new:")) {
            return "CONSTRUCTOR_RESULT";
        }
        if ("CONSTANT".equals(kind)) {
            return "LITERAL";
        }
        if ("CONTAINER_ELEMENT".equals(kind) && displayName != null) {
            return displayName.contains("[*]") ? "COLLECTION_ELEMENT" : "MAP_ENTRY";
        }
        return kind;
    }

    // 作用：判断展示名是否属于内部版本；逻辑：匹配内部版本前缀。
    private boolean isInternalVersion(String displayName, String kind) {
        if ("PHI".equals(kind)) {
            return true;
        }
        return displayName != null
                && (displayName.startsWith("phi:")
                || displayName.startsWith("branch:")
                || displayName.startsWith("before:"));
    }

    // 作用：返回索引代数；逻辑：直接暴露保存的 generation。
    int generation() {
        return generation;
    }

    // 作用：返回图状态；逻辑：直接暴露保存的状态字符串。
    String graphStatus() {
        return graphStatus;
    }

    private record FieldExpansion(List<String> path, Step emptyStep, Step pathStep) {
    }

    private record NestedContainer(ValueNodeRecord root, String relativePath) {
    }
}
