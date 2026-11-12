package io.fieldtracegraph.core.model;

// 作用：分析或索引过程中的诊断信息，带是否阻断的标记。
// 使用场景：解析失败、classpath 索引截断、索引中止时向用户解释原因。

public record Diagnostic(
        String code,
        String message,
        String relativePath,
        Integer line,
        boolean retryable,
        boolean blocking
) {
}
