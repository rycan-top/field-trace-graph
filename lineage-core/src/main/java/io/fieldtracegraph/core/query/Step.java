package io.fieldtracegraph.core.query;

import io.fieldtracegraph.core.model.TraceEdge;
import io.fieldtracegraph.core.model.TraceEdgeCategory;
import io.fieldtracegraph.core.model.EdgeEvidence;
import io.fieldtracegraph.core.model.Provenance;
import io.fieldtracegraph.core.support.Ids;

import java.util.List;

// 作用：表示遍历中的一步。
// 使用场景：路径记录、结果构造与调试追踪过程。

/**
 * A single directed graph step used during traversal. It carries the evidence
 * location and whether the field path propagates across the step.
 */
record Step(
        ValueRef source,
        ValueRef target,
        String edgeType,
        String operation,
        String resolution,
        String evidenceId,
        String fileId,
        int lineStart,
        int columnStart,
        int lineEnd,
        int columnEnd,
        String effectLocation,
        boolean propagateFields,
        String callSiteId,
        CallContextDirection callContextDirection,
        Integer argIndex,
        String provenance,
        List<EdgeEvidence> evidence
) {
    // 作用：规范化遍历步字段；逻辑：来源转为枚举名，证据列表空值转空列表并复制。
    Step {
        provenance = Provenance.from(provenance).name();
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    Step(
            ValueRef source,
            ValueRef target,
            String edgeType,
            String operation,
            String resolution,
            String evidenceId,
            String fileId,
            int lineStart,
            int columnStart,
            int lineEnd,
            int columnEnd,
            boolean propagateFields
    ) {
        this(source, target, edgeType, operation, resolution, evidenceId, fileId,
                lineStart, columnStart, lineEnd, columnEnd, "", propagateFields,
                "", CallContextDirection.NONE, null, Provenance.SOURCE.name(), List.of());
    }

    Step(
            ValueRef source,
            ValueRef target,
            String edgeType,
            String operation,
            String resolution,
            String evidenceId,
            String fileId,
            int lineStart,
            int columnStart,
            int lineEnd,
            int columnEnd,
            String effectLocation,
            boolean propagateFields,
            String callSiteId,
            CallContextDirection callContextDirection,
            Integer argIndex
    ) {
        this(source, target, edgeType, operation, resolution, evidenceId, fileId,
                lineStart, columnStart, lineEnd, columnEnd, effectLocation,
                propagateFields, callSiteId, callContextDirection, argIndex,
                Provenance.SOURCE.name(), List.of());
    }

    Step(
            ValueRef source,
            ValueRef target,
            String edgeType,
            String operation,
            String resolution,
            String evidenceId,
            String fileId,
            int lineStart,
            int columnStart,
            int lineEnd,
            int columnEnd,
            boolean propagateFields,
            String callSiteId,
            CallContextDirection callContextDirection
    ) {
        this(source, target, edgeType, operation, resolution, evidenceId, fileId,
                lineStart, columnStart, lineEnd, columnEnd, "", propagateFields,
                callSiteId, callContextDirection, null, Provenance.SOURCE.name(), List.of());
    }

    Step(
            ValueRef source,
            ValueRef target,
            String edgeType,
            String operation,
            String resolution,
            String evidenceId,
            String fileId,
            int lineStart,
            int columnStart,
            int lineEnd,
            int columnEnd,
            boolean propagateFields,
            String provenance,
            List<EdgeEvidence> evidence
    ) {
        this(source, target, edgeType, operation, resolution, evidenceId, fileId,
                lineStart, columnStart, lineEnd, columnEnd, "", propagateFields,
                "", CallContextDirection.NONE, null, provenance, evidence);
    }

    // 作用：把内部步转换为输出边；逻辑：填充展示字段、方向与分类，供结果序列化。
    TraceEdge toEdge(
            ValueRef src,
            ValueRef dst,
            String direction,
            int depth,
            String srcScope,
            String dstScope,
            String sourceFile
    ) {
        boolean resolved = !"UNRESOLVED".equals(resolution);
        boolean bridged = callSiteId != null && !callSiteId.isBlank();
        return new TraceEdge(
                Ids.sha256(src.key(), dst.key(), edgeType),
                src.key(),
                dst.key(),
                edgeType,
                operation,
                direction,
                resolution,
                evidenceId,
                resolved,
                bridged,
                srcScope,
                dstScope,
                sourceFile,
                lineStart,
                lineEnd,
                effectLocation,
                depth,
                argIndex,
                argIndex == null ? 0 : 1,
                TraceEdgeCategory.fromEdgeType(edgeType),
                provenance,
                evidence
        );
    }

    // 作用：复制遍历步并替换源引用；逻辑：保留其余字段，只更新 source。
    Step withSource(ValueRef newSource) {
        return new Step(newSource, target, edgeType, operation, resolution, evidenceId, fileId,
                lineStart, columnStart, lineEnd, columnEnd, effectLocation, propagateFields,
                callSiteId, callContextDirection, argIndex, provenance, evidence);
    }

    // 作用：复制遍历步并替换目标引用；逻辑：保留其余字段，只更新 target。
    Step withTarget(ValueRef newTarget) {
        return new Step(source, newTarget, edgeType, operation, resolution, evidenceId, fileId,
                lineStart, columnStart, lineEnd, columnEnd, effectLocation, propagateFields,
                callSiteId, callContextDirection, argIndex, provenance, evidence);
    }

    /**
     * Same evidence, opposite endpoints. Used when a backward traversal
     * projects an object root onto one of its selected fields.
     */
    // 作用：反转遍历步的方向与端点；逻辑：交换源目标并翻转调用上下文方向。
    Step reversed() {
        return new Step(target, source, edgeType, operation, resolution, evidenceId, fileId,
                lineStart, columnStart, lineEnd, columnEnd, effectLocation, propagateFields,
                callSiteId, callContextDirection, argIndex, provenance, evidence);
    }
}
