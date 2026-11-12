package io.fieldtracegraph.core.model;

// 作用：单条证据差异的描述。
// 使用场景：evidence diff 命令列出新增、删除或变化的证据。

public record EvidenceDiffItem(
        String logicalEdgeKey,
        String evidenceKind,
        String adapter,
        String coverage,
        String observedAt,
        boolean presentInStaticGraph
) {
}
