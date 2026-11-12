package io.fieldtracegraph.core.model;

import java.util.List;

// 作用：查询结果图中的边。
// 使用场景：trace 的 text/JSON 输出与候选边门禁判断。

// 作用：结果图节点与边；场景：JSON 结构化输出。
public record TraceEdge(
        String edgeId,
        String srcId,
        String dstId,
        String edgeType,
        String operation,
        String direction,
        String resolution,
        String evidenceId,
        boolean resolved,
        boolean bridged,
        String srcScope,
        String dstScope,
        String sourceFile,
        int lineStart,
        int lineEnd,
        String effectLocation,
        int depth,
        Integer argIndex,
        int argLevel,
        TraceEdgeCategory category,
        String provenance,
        List<EdgeEvidence> evidence
) {
    // 作用：规范化结果边字段；逻辑：来源转为枚举名，证据列表空值转空列表并复制。
    public TraceEdge {
        provenance = Provenance.from(provenance).name();
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    // 作用：构造默认来源为源码的结果边；逻辑：补上 SOURCE 与空证据后转调完整构造器。
    public TraceEdge(
            String edgeId,
            String srcId,
            String dstId,
            String edgeType,
            String operation,
            String direction,
            String resolution,
            String evidenceId,
            boolean resolved,
            boolean bridged,
            String srcScope,
            String dstScope,
            String sourceFile,
            int lineStart,
            int lineEnd,
            String effectLocation,
            int depth,
            Integer argIndex,
            int argLevel,
            TraceEdgeCategory category
    ) {
        this(edgeId, srcId, dstId, edgeType, operation, direction, resolution,
                evidenceId, resolved, bridged, srcScope, dstScope, sourceFile,
                lineStart, lineEnd, effectLocation, depth, argIndex, argLevel,
                category, Provenance.SOURCE.name(), List.of());
    }

    // 作用：复制结果边并替换操作描述；逻辑：保留其余字段，只更新 operation。
    public TraceEdge withOperation(String value) {
        return new TraceEdge(edgeId, srcId, dstId, edgeType, value, direction, resolution,
                evidenceId, resolved, bridged, srcScope, dstScope, sourceFile,
                lineStart, lineEnd, effectLocation, depth, argIndex, argLevel, category,
                provenance, evidence);
    }

    // 作用：给结果边附加聚合后的证据；逻辑：复制边并写入选中的置信度、来源与完整证据列表。
    public TraceEdge withEvidence(String selectedResolution, String selectedProvenance,
                                  List<EdgeEvidence> selectedEvidence) {
        return new TraceEdge(edgeId, srcId, dstId, edgeType, operation, direction,
                selectedResolution, evidenceId, resolved, bridged, srcScope, dstScope,
                sourceFile, lineStart, lineEnd, effectLocation, depth, argIndex,
                argLevel, category, selectedProvenance, selectedEvidence);
    }
}
