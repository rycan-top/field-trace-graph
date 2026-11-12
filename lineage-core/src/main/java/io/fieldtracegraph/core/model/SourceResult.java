package io.fieldtracegraph.core.model;

// 作用：源码证据查询结果。
// 使用场景：source 命令的 text/JSON 输出。

public record SourceResult(
        String relativePath,
        String contentHash,
        int lineStart,
        int lineEnd,
        String text
) {
}
