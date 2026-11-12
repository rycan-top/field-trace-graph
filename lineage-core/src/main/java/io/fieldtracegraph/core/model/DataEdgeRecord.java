package io.fieldtracegraph.core.model;

// 作用：字段值数据边记录。
// 使用场景：value_data_edge 表的一行，也是血缘遍历的基本单位。

public record DataEdgeRecord(
        String edgeId,
        String scopeSymbolId,
        String srcValueId,
        String dstValueId,
        String edgeType,
        String operation,
        String resolution,
        String evidenceId,
        String branchId,
        String branchCondition,
        String fileId,
        int lineStart,
        int columnStart,
        int lineEnd,
        int columnEnd,
        int statementOrdinal,
        String provenance
) {
    // 作用：构造默认来源为源码的数据边；逻辑：把 provenance 补为 SOURCE 后转调完整构造器。
    public DataEdgeRecord(
            String edgeId,
            String scopeSymbolId,
            String srcValueId,
            String dstValueId,
            String edgeType,
            String operation,
            String resolution,
            String evidenceId,
            String branchId,
            String branchCondition,
            String fileId,
            int lineStart,
            int columnStart,
            int lineEnd,
            int columnEnd,
            int statementOrdinal
    ) {
        this(edgeId, scopeSymbolId, srcValueId, dstValueId, edgeType, operation,
                resolution, evidenceId, branchId, branchCondition, fileId,
                lineStart, columnStart, lineEnd, columnEnd, statementOrdinal,
                Provenance.SOURCE.name());
    }

    // 作用：规范化数据边的置信度字段；逻辑：把 resolution 与 provenance 统一转换成枚举名。
    public DataEdgeRecord {
        resolution = Resolution.from(resolution).name();
        provenance = Provenance.from(provenance).name();
    }

    // 作用：生成逻辑边键；逻辑：把作用域、源、目标与边类型用分隔符拼成稳定键，供多份证据聚合。
    public String logicalEdgeKey() {
        return scopeSymbolId + '\u001f' + srcValueId + '\u001f' + dstValueId
                + '\u001f' + edgeType;
    }

    // 作用：复制数据边并替换证据来源；逻辑：保留其余字段，只更新 provenance。
    public DataEdgeRecord withProvenance(String value) {
        return new DataEdgeRecord(
                edgeId,
                scopeSymbolId,
                srcValueId,
                dstValueId,
                edgeType,
                operation,
                resolution,
                evidenceId,
                branchId,
                branchCondition,
                fileId,
                lineStart,
                columnStart,
                lineEnd,
                columnEnd,
                statementOrdinal,
                value
        );
    }

    /**
     * Dependency edges explain conditions or call inputs, but they do not
     * prove that one field value was copied from another. Field lineage
     * excludes them unless dependency analysis is explicitly requested.
     */
    // 作用：判断该边是否只是依赖关系；逻辑：边类型不属于字段值流时返回 true。
    public boolean dependencyOnly() {
        return "CALL_INPUT_USES".equals(edgeType)
                || "CONDITION_USES".equals(edgeType)
                || "MUTATES_PARAMETER".equals(edgeType);
    }
}
