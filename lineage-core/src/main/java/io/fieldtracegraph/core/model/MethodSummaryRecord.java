package io.fieldtracegraph.core.model;

import java.util.List;

// 作用：方法级摘要记录。
// 使用场景：method_summary 表存储，并支撑方法检索与概览。

public record MethodSummaryRecord(
        String summaryId,
        String scopeSymbolId,
        String fileId,
        String parametersJson,
        String returnValueId,
        int returnOrder,
        String branchCondition,
        String fieldWritesJson,
        List<ParameterMutationRecord> parameterMutations,
        int lineStart,
        int columnStart,
        int lineEnd,
        int columnEnd
) {
}
