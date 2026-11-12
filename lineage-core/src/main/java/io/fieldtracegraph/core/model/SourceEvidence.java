package io.fieldtracegraph.core.model;

// 作用：单条源码证据。
// 使用场景：source 命令展示结论对应的文件、行号与代码片段。

public record SourceEvidence(
        String evidenceId,
        String relativePath,
        String contentHash,
        int lineStart,
        int columnStart,
        int lineEnd,
        int columnEnd,
        String expression,
        String effectLocation
) {
}
