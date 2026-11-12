package io.fieldtracegraph.core.model;

// 作用：证据缺失或冲突的诊断项。
// 使用场景：trace 结果附带诊断，提示哪条结论证据不足。

/**
 * A problem detected while resolving edge evidence for one trace.
 *
 * 作用：证据缺失/冲突诊断；场景：结果附带的诊断区
 */
public record TraceEvidenceIssue(
        String code,
        String message,
        String relativePath,
        int lineStart
) {
}
