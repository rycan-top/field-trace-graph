package io.fieldtracegraph.cli.dto;

// 作用：描述一条待导入的证据。
// 使用场景：evidence import 的输入解析与校验。

public record EvidenceImportItem(
        String evidenceId,
        String logicalEdgeKey,
        String scopeSymbolId,
        String sourceEdgeId,
        String evidenceKind,
        String adapter,
        String rule,
        String coverage,
        String observedAt,
        String detailsJson
) {
}
