package io.fieldtracegraph.core.query;

import io.fieldtracegraph.core.model.CallEdgeRecord;
import io.fieldtracegraph.core.model.MethodSummaryRecord;
import io.fieldtracegraph.core.model.ParameterMutationRecord;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：把调用点的实参绑定到被调方法的形参。
// 使用场景：跨方法追踪时接续值流，避免链路在方法边界断裂。

/**
 * Builds the cross-call bridges that join caller arguments, return values,
 * receiver-field writes and callee parameter effects to the corresponding
 * caller values. Every bridge is keyed by call-site identity so two different
 * call instances never share caller-owned nodes.
 */
final class CallArgumentBinder {
    // 作用：为整张图建立调用参数桥接；逻辑：先汇总有效参数变更，再依次绑定普通实参、返回值、参数副作用与接收者字段写入。
    List<Bridge> bind(LineageGraph graph) {
        Map<String, List<ValueNodeRecord>> paramsByScope = new HashMap<>();
        Map<String, List<ValueNodeRecord>> returnsByScope = new HashMap<>();
        Map<String, List<ValueNodeRecord>> fieldWritesByScope = new HashMap<>();
        Map<String, List<MethodSummaryRecord>> summariesByScope = new HashMap<>();
        Map<String, Map<String, ValueNodeRecord>> nodesByPathAndScope = new HashMap<>();
        Set<String> fieldWriteIds = new HashSet<>();
        for (var edge : graph.dataEdges()) {
            if ("FIELD_WRITE".equals(edge.edgeType())
                    || "MUTATES_PARAMETER".equals(edge.edgeType())) {
                fieldWriteIds.add(edge.dstValueId());
            }
        }
        for (ValueNodeRecord node : graph.valueNodes()) {
            if ("PARAMETER".equals(node.valueKind())) {
                paramsByScope.computeIfAbsent(node.scopeSymbolId(), key -> new ArrayList<>()).add(node);
            } else if ("RETURN".equals(node.valueKind())) {
                returnsByScope.computeIfAbsent(node.scopeSymbolId(), key -> new ArrayList<>()).add(node);
            }
            if ("FIELD".equals(node.valueKind()) && fieldWriteIds.contains(node.valueId())) {
                fieldWritesByScope.computeIfAbsent(node.scopeSymbolId(), key -> new ArrayList<>()).add(node);
            }
            if ((node.accessPath() != null && !node.accessPath().isEmpty())
                    || "CONTAINER_ELEMENT".equals(node.valueKind())) {
                nodesByPathAndScope
                        .computeIfAbsent(node.scopeSymbolId(), key -> new LinkedHashMap<>())
                        .putIfAbsent(basePath(node), node);
            }
        }
        for (MethodSummaryRecord summary : graph.methodSummaries()) {
            summariesByScope.computeIfAbsent(summary.scopeSymbolId(), key -> new ArrayList<>())
                    .add(summary);
        }
        paramsByScope.values().forEach(list -> list.sort(
                Comparator.comparingInt(ValueNodeRecord::lineStart)
                        .thenComparingInt(ValueNodeRecord::columnStart)
        ));
        Map<String, List<ParameterMutationRecord>> effectiveMutations =
                effectiveParameterMutations(graph, paramsByScope, summariesByScope);
        Map<String, Map<String, Integer>> paramIndexByScope = new HashMap<>();
        for (var entry : paramsByScope.entrySet()) {
            Map<String, Integer> indexByName = new HashMap<>();
            for (int i = 0; i < entry.getValue().size(); i++) {
                indexByName.put(entry.getValue().get(i).canonicalValue(), i);
            }
            paramIndexByScope.put(entry.getKey(), indexByName);
        }

        List<Bridge> bridges = new ArrayList<>();
        for (CallEdgeRecord call : graph.callEdges()) {
            if (call.calleeSymbolId() == null) {
                continue;
            }
            List<ValueNodeRecord> params = paramsByScope.getOrDefault(call.calleeSymbolId(), List.of());
            bindPlainArguments(call, params, bridges);
            bindReturns(call, returnsByScope, bridges);
            Set<String> summaryTargets = bindParameterEffects(
                    graph,
                    call,
                    params,
                    effectiveMutations.getOrDefault(call.calleeSymbolId(), List.of()),
                    nodesByPathAndScope.getOrDefault(call.calleeSymbolId(), Map.of()),
                    bridges
            );
            bindReceiverFieldWrites(
                    call,
                    params,
                    paramIndexByScope.getOrDefault(call.calleeSymbolId(), Map.of()),
                    fieldWritesByScope.getOrDefault(call.calleeSymbolId(), List.of()),
                    summaryTargets,
                    bridges
            );
        }
        return List.copyOf(bridges);
    }

    /**
     * Propagates a callee's parameter mutation to methods that forward one of
     * their own parameters unchanged. This is required for chains such as
     * {@code getCouponsNew -> handleTabDefaultStatus -> updateTabDefaultStatusByAdId}
     * where only the last method writes {@code tabs[*].defaultOn}.
     */
    // 作用：计算每个方法真正生效的参数变更；逻辑：合并直接与间接变更并按方法分组。
    private Map<String, List<ParameterMutationRecord>> effectiveParameterMutations(
            LineageGraph graph,
            Map<String, List<ValueNodeRecord>> paramsByScope,
            Map<String, List<MethodSummaryRecord>> summariesByScope
    ) {
        Map<String, List<CallEdgeRecord>> callsByCaller = new HashMap<>();
        for (CallEdgeRecord call : graph.callEdges()) {
            if (call.calleeSymbolId() != null) {
                callsByCaller.computeIfAbsent(call.callerSymbolId(), key -> new ArrayList<>()).add(call);
            }
        }
        Map<String, List<ParameterMutationRecord>> memo = new HashMap<>();
        for (String scopeId : paramsByScope.keySet()) {
            collectEffectiveMutations(
                    scopeId,
                    paramsByScope,
                    summariesByScope,
                    callsByCaller,
                    memo,
                    new HashSet<>()
            );
        }
        return memo;
    }

    // 作用：收集一个方法的有效参数变更；逻辑：沿调用链递归合并，避免重复与环。
    private List<ParameterMutationRecord> collectEffectiveMutations(
            String scopeId,
            Map<String, List<ValueNodeRecord>> paramsByScope,
            Map<String, List<MethodSummaryRecord>> summariesByScope,
            Map<String, List<CallEdgeRecord>> callsByCaller,
            Map<String, List<ParameterMutationRecord>> memo,
            Set<String> visiting
    ) {
        List<ParameterMutationRecord> cached = memo.get(scopeId);
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(scopeId)) {
            return List.of();
        }
        Map<String, ParameterMutationRecord> mutations = new LinkedHashMap<>();
        for (MethodSummaryRecord summary : summariesByScope.getOrDefault(scopeId, List.of())) {
            for (ParameterMutationRecord mutation : summary.parameterMutations()) {
                mutations.putIfAbsent(effectiveMutationKey(mutation), mutation);
            }
        }
        List<ValueNodeRecord> parameters = paramsByScope.getOrDefault(scopeId, List.of());
        for (CallEdgeRecord call : callsByCaller.getOrDefault(scopeId, List.of())) {
            List<ParameterMutationRecord> calleeMutations = collectEffectiveMutations(
                    call.calleeSymbolId(),
                    paramsByScope,
                    summariesByScope,
                    callsByCaller,
                    memo,
                    visiting
            );
            for (int argumentIndex = 0;
                 argumentIndex < call.argumentValueIds().size();
                 argumentIndex++) {
                ValueNodeRecord parameter = parameterAt(parameters, argumentIndex, call, call.argumentValueIds().get(argumentIndex));
                if (parameter == null) {
                    continue;
                }
                for (ParameterMutationRecord calleeMutation : calleeMutations) {
                    if (calleeMutation.parameterIndex() != argumentIndex) {
                        continue;
                    }
                    ParameterMutationRecord forwarded = new ParameterMutationRecord(
                            parameterIndex(parameters, parameter),
                            parameter.canonicalValue(),
                            calleeMutation.targetPath(),
                            calleeMutation.operation(),
                            calleeMutation.lineStart(),
                            calleeMutation.columnStart(),
                            calleeMutation.lineEnd(),
                            calleeMutation.columnEnd()
                    );
                    mutations.putIfAbsent(effectiveMutationKey(forwarded), forwarded);
                }
            }
        }
        visiting.remove(scopeId);
        List<ParameterMutationRecord> result = List.copyOf(mutations.values());
        memo.put(scopeId, result);
        return result;
    }

    // 作用：按位置取形参节点；逻辑：从参数列表中定位并返回，越界时返回 null。
    private ValueNodeRecord parameterAt(
            List<ValueNodeRecord> parameters,
            int argumentIndex,
            CallEdgeRecord call,
            String argumentNodeId
    ) {
        if (argumentNodeId == null) {
            return null;
        }
        return parameters.stream()
                .filter(parameter -> argumentNodeId.equals(parameter.valueId()))
                .findFirst()
                .orElse(null);
    }

    // 作用：计算形参在列表中的位置；逻辑：按节点标识匹配并返回下标。
    private int parameterIndex(List<ValueNodeRecord> parameters, ValueNodeRecord parameter) {
        for (int index = 0; index < parameters.size(); index++) {
            if (parameters.get(index).valueId().equals(parameter.valueId())) {
                return index;
            }
        }
        return -1;
    }

    // 作用：生成参数变更的归一化键；逻辑：把方法、参数下标与字段路径拼成字符串。
    private String effectiveMutationKey(ParameterMutationRecord mutation) {
        return mutation.parameterIndex() + "\u0001"
                + mutation.targetPath() + "\u0001"
                + mutation.operation() + "\u0001"
                + mutation.lineStart() + "\u0001"
                + mutation.columnStart();
    }

    // 作用：绑定普通实参到形参；逻辑：逐个调用点匹配参数位置并生成桥接边。
    private void bindPlainArguments(
            CallEdgeRecord call,
            List<ValueNodeRecord> params,
            List<Bridge> bridges
    ) {
        for (int i = 0; i < call.argumentValueIds().size() && i < params.size(); i++) {
            String argumentNodeId = call.argumentValueIds().get(i);
            ValueNodeRecord parameter = params.get(i);
            if (argumentNodeId == null || parameter == null) {
                continue;
            }
            bridges.add(new Bridge(argumentNodeId, parameter.valueId(), new Step(
                    new ValueRef(argumentNodeId, List.of()),
                    new ValueRef(parameter.valueId(), List.of()),
                    "ARGUMENT_TO",
                    "argument passed to callee parameter",
                    call.resolution(),
                    Ids.sha256(call.callSiteId(), "arg", String.valueOf(i)),
                    call.fileId(),
                    call.lineStart(),
                    call.columnStart(),
                    call.lineEnd(),
                    call.columnEnd(),
                    "",
                    true,
                    call.callSiteId(),
                    CallContextDirection.ENTER,
                    i
            ), false));
        }
    }

    // 作用：绑定返回值到调用结果；逻辑：把被调方返回节点连接到调用点结果节点。
    private void bindReturns(
            CallEdgeRecord call,
            Map<String, List<ValueNodeRecord>> returnsByScope,
            List<Bridge> bridges
    ) {
        if (call.resultValueId() == null) {
            return;
        }
        for (ValueNodeRecord returnNode : returnsByScope.getOrDefault(call.calleeSymbolId(), List.of())) {
            bridges.add(new Bridge(returnNode.valueId(), call.resultValueId(), new Step(
                    new ValueRef(returnNode.valueId(), List.of()),
                    new ValueRef(call.resultValueId(), List.of()),
                    "RETURN_FROM",
                    "return value from callee",
                    call.resolution(),
                    Ids.sha256(call.callSiteId(), "return", returnNode.valueId()),
                    call.fileId(),
                    call.lineStart(),
                    call.columnStart(),
                    call.lineEnd(),
                    call.columnEnd(),
                    "",
                    true,
                    call.callSiteId(),
                    CallContextDirection.LEAVE,
                    null
            ), false));
        }
    }

    // 作用：绑定参数被修改后的回写；逻辑：沿有效变更找到调用方对应实参并生成边。
    private Set<String> bindParameterEffects(
            LineageGraph graph,
            CallEdgeRecord call,
            List<ValueNodeRecord> params,
            List<ParameterMutationRecord> parameterMutations,
            Map<String, ValueNodeRecord> nodesByPath,
            List<Bridge> bridges
    ) {
        Set<String> targets = new HashSet<>();
        Set<String> effects = new HashSet<>();
        for (ParameterMutationRecord mutation : parameterMutations) {
                String effectKey = mutation.parameterIndex() + "\u0001"
                        + mutation.targetPath() + "\u0001"
                        + mutation.operation() + "\u0001"
                        + mutation.lineStart() + "\u0001"
                        + mutation.columnStart();
                if (!effects.add(effectKey)) {
                    continue;
                }
                int parameterIndex = mutation.parameterIndex();
                if (parameterIndex < 0
                        || parameterIndex >= params.size()
                        || parameterIndex >= call.argumentValueIds().size()) {
                    continue;
                }
                String argumentNodeId = call.argumentValueIds().get(parameterIndex);
                if (argumentNodeId == null) {
                    continue;
                }
                String targetPath = mutation.parameterName() + mutation.targetPath();
                ValueNodeRecord mutationTarget = nodesByPath.get(targetPath);
                if (mutationTarget == null && mutation.targetPath().isBlank()) {
                    mutationTarget = params.get(parameterIndex);
                }
                List<String> sourceFields = List.of();
                if (mutationTarget == null && mutation.targetPath().startsWith("[*]")) {
                    mutationTarget = params.get(parameterIndex);
                    sourceFields = callerFields(mutation.targetPath().substring(3));
                }
                if (mutationTarget == null) {
                    continue;
                }
                String targetKey = argumentNodeId + "\u0001" + parameterIndex
                        + "\u0001" + mutationTarget.valueId();
                targets.add(targetKey);
                EffectTarget effectTarget = callerEffectTarget(
                        graph, call, argumentNodeId, mutation.targetPath()
                );
                bridges.add(new Bridge(mutationTarget.valueId(), effectTarget.nodeId(), new Step(
                        new ValueRef(mutationTarget.valueId(), sourceFields),
                        new ValueRef(effectTarget.nodeId(), effectTarget.fields()),
                        mutation.operation(),
                        "callee parameter effect at "
                                + effectLocation(graph, call.calleeSymbolId(), mutation),
                        call.resolution(),
                        Ids.sha256(
                                call.callSiteId(),
                                "parameter-effect",
                                String.valueOf(parameterIndex),
                                mutation.operation(),
                                mutation.targetPath()
                        ),
                        call.fileId(),
                        call.lineStart(),
                        call.columnStart(),
                        call.lineEnd(),
                        call.columnEnd(),
                        effectLocation(graph, call.calleeSymbolId(), mutation),
                        false,
                        call.callSiteId(),
                        CallContextDirection.LEAVE,
                        parameterIndex
                ), false));
            }
        return targets;
    }

    /**
     * A callee mutating {@code list[*].field} mutates the caller's element
     * root, not the List object itself. Project wildcard parameter effects onto
     * the matching CONTAINER_ELEMENT node so field traversal can continue
     * through the same identity the user selects.
     */
    // 作用：确定调用方受影响的目标；逻辑：根据实参路径与变更字段计算接收者节点与字段。
    private EffectTarget callerEffectTarget(
            LineageGraph graph,
            CallEdgeRecord call,
            String argumentNodeId,
            String targetPath
    ) {
        String path = targetPath == null ? "" : targetPath;
        if (!path.startsWith("[*]")) {
            return new EffectTarget(argumentNodeId, callerFields(path));
        }
        ValueNodeRecord argument = graph.valueNodes().stream()
                .filter(node -> argumentNodeId.equals(node.valueId()))
                .findFirst()
                .orElse(null);
        if (argument == null) {
            return new EffectTarget(argumentNodeId, callerFields(path));
        }
        String elementValue = argument.canonicalValue() + "[*]";
        return graph.valueNodes().stream()
                .filter(node -> call.callerSymbolId().equals(node.scopeSymbolId()))
                .filter(node -> "CONTAINER_ELEMENT".equals(node.valueKind()))
                .filter(node -> elementValue.equals(node.canonicalValue()))
                .findFirst()
                .map(node -> new EffectTarget(node.valueId(), callerFields(path.substring(3))))
                .orElseGet(() -> new EffectTarget(argumentNodeId, callerFields(path)));
    }

    // 作用：绑定接收者字段写入；逻辑：把被调方对 this 字段的写入映射回调用方对象。
    private void bindReceiverFieldWrites(
            CallEdgeRecord call,
            List<ValueNodeRecord> params,
            Map<String, Integer> paramIndexByName,
            List<ValueNodeRecord> fieldWrites,
            Set<String> summaryTargets,
            List<Bridge> bridges
    ) {
        for (ValueNodeRecord fieldWrite : fieldWrites) {
            if ("this".equals(fieldWrite.rootName())) {
                if (call.receiverValueId() != null) {
                    bridges.add(fieldWriteBridge(
                            call,
                            fieldWrite,
                            call.receiverValueId(),
                            "field",
                            null
                    ));
                }
                continue;
            }
            Integer parameterIndex = parameterIndexFor(fieldWrite, params, paramIndexByName);
            if (parameterIndex == null || parameterIndex >= call.argumentValueIds().size()) {
                continue;
            }
            String argumentNodeId = call.argumentValueIds().get(parameterIndex);
            if (argumentNodeId == null) {
                continue;
            }
            if (summaryTargets.contains(
                    argumentNodeId + "\u0001" + parameterIndex + "\u0001" + fieldWrite.valueId())) {
                continue;
            }
            bridges.add(fieldWriteBridge(
                    call,
                    fieldWrite,
                    argumentNodeId,
                    "arg" + parameterIndex,
                    parameterIndex
            ));
        }
    }

    // 作用：查找实参对应的形参下标；逻辑：按节点标识或路径匹配参数列表。
    private Integer parameterIndexFor(
            ValueNodeRecord fieldWrite,
            List<ValueNodeRecord> params,
            Map<String, Integer> paramIndexByName
    ) {
        String root = fieldWrite.rootName();
        if (root == null) {
            return null;
        }
        Integer exact = paramIndexByName.get(root);
        if (exact != null) {
            return exact;
        }
        for (int index = 0; index < params.size(); index++) {
            String parameter = params.get(index).canonicalValue();
            if (root.startsWith(parameter + ".") || root.startsWith(parameter + "[")) {
                return index;
            }
        }
        return null;
    }

    // 作用：构造字段写入桥接；逻辑：创建调用方字段到被调方字段的边并标注证据。
    private Bridge fieldWriteBridge(
            CallEdgeRecord call,
            ValueNodeRecord fieldWrite,
            String targetNodeId,
            String evidenceSuffix,
            Integer argIndex
    ) {
        List<String> fieldPath = fieldWrite.accessPath() == null ? List.of() : fieldWrite.accessPath();
        return new Bridge(fieldWrite.valueId(), targetNodeId, new Step(
                new ValueRef(fieldWrite.valueId(), List.of()),
                new ValueRef(targetNodeId, fieldPath),
                "FIELD_WRITE",
                "callee writes receiver field",
                call.resolution(),
                Ids.sha256(call.callSiteId(), evidenceSuffix, String.join(".", fieldPath)),
                call.fileId(),
                call.lineStart(),
                call.columnStart(),
                call.lineEnd(),
                call.columnEnd(),
                "",
                false,
                call.callSiteId(),
                CallContextDirection.LEAVE,
                argIndex
        ), false);
    }

    // 作用：生成副作用位置描述；逻辑：把方法、字段与调用点拼成可读字符串。
    private String effectLocation(
            LineageGraph graph,
            String scopeId,
            ParameterMutationRecord mutation
    ) {
        String fileId = graph.valueNodes().stream()
                .filter(node -> scopeId.equals(node.scopeSymbolId()))
                .map(ValueNodeRecord::fileId)
                .findFirst()
                .orElse(null);
        SourceFileRecord file = fileId == null ? null : graph.sourceFiles().get(fileId);
        String path = file == null ? String.valueOf(fileId) : file.relativePath();
        return path + ":" + mutation.lineStart() + ":" + mutation.columnStart();
    }

    // 作用：把目标路径拆成调用方字段序列；逻辑：按点号切分并过滤空段。
    private List<String> callerFields(String targetPath) {
        String relative = targetPath == null ? "" : targetPath;
        while (relative.startsWith(".")) {
            relative = relative.substring(1);
        }
        if (relative.isBlank()) {
            return List.of();
        }
        if (relative.startsWith("[")) {
            return List.of(relative);
        }
        return List.of(relative.split("\\."));
    }

    // 作用：取节点的基础路径；逻辑：去掉字段后缀，只保留根表达式。
    private String basePath(ValueNodeRecord node) {
        String canonical = node.canonicalValue() == null ? "" : node.canonicalValue();
        int version = canonical.indexOf('@');
        if (version >= 0) {
            canonical = canonical.substring(0, version);
        }
        if (node.rootName() != null && !node.rootName().isBlank()) {
            if (node.accessPath() == null || node.accessPath().isEmpty()) {
                return node.rootName();
            }
            return node.rootName() + "." + String.join(".", node.accessPath());
        }
        return canonical;
    }

    record Bridge(String srcValueId, String dstValueId, Step step, boolean outgoingOnly) {
    }

    private record EffectTarget(String nodeId, List<String> fields) {
    }
}
