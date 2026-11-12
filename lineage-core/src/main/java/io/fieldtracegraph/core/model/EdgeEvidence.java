package io.fieldtracegraph.core.model;

// 作用：一条逻辑边的一份独立证据，可与其他来源的证据叠加。
// 使用场景：写入 edge_evidence 表，让源码、适配器、运行时与人工契约证据并存。

/**
 * One independent piece of evidence for a logical field edge. Evidence rows
 * are additive: a runtime hit never replaces the static candidate that led to
 * it, and a manual contract never hides the generated-source rule it explains.
 *
 *
 * 作用：一条逻辑边的一份独立证据；场景：写入 edge_evidence，支持多证据叠加。
 *
 */
public record EdgeEvidence(
        String evidenceId,
        String logicalEdgeKey,
        String sourceEdgeId,
        String resolution,
        String provenance,
        String evidenceKind,
        String adapter,
        String rule,
        String fileId,
        int lineStart,
        int columnStart,
        int lineEnd,
        int columnEnd,
        String detailsJson,
        String coverage,
        String observedAt,
        String scopeSymbolId
) {
    // 作用：规范化证据字段；逻辑：置信度与来源转为枚举名，空 details 补成空 JSON。
    public EdgeEvidence {
        resolution = Resolution.from(resolution).name();
        provenance = Provenance.from(provenance).name();
        detailsJson = detailsJson == null || detailsJson.isBlank() ? "{}" : detailsJson;
    }

    // 作用：由数据边生成一份源码证据；逻辑：复制位置、操作与作用域信息，并标记为 STATIC_SOURCE。
    public static EdgeEvidence source(DataEdgeRecord edge) {
        return new EdgeEvidence(
                edge.edgeId() + ":SOURCE",
                edge.logicalEdgeKey(),
                edge.edgeId(),
                edge.resolution(),
                edge.provenance(),
                "STATIC_SOURCE",
                null,
                edge.operation(),
                edge.fileId(),
                edge.lineStart(),
                edge.columnStart(),
                edge.lineEnd(),
                edge.columnEnd(),
                "{}",
                "STATIC_SOURCE",
                null,
                edge.scopeSymbolId()
        );
    }

    // 作用：构造证据并推导作用域；逻辑：委托主构造器，作用域从逻辑边键中解析。
    public EdgeEvidence(
            String evidenceId,
            String logicalEdgeKey,
            String sourceEdgeId,
            String resolution,
            String provenance,
            String evidenceKind,
            String adapter,
            String rule,
            String fileId,
            int lineStart,
            int columnStart,
            int lineEnd,
            int columnEnd,
            String detailsJson,
            String coverage,
            String observedAt
    ) {
        this(evidenceId, logicalEdgeKey, sourceEdgeId, resolution, provenance,
                evidenceKind, adapter, rule, fileId, lineStart, columnStart,
                lineEnd, columnEnd, detailsJson, coverage, observedAt,
                scopeFromLogicalKey(logicalEdgeKey));
    }

    // 作用：从逻辑边键提取作用域；逻辑：按分隔符取第一段，缺失或为空时返回 unknown。
    public static String scopeFromLogicalKey(String logicalEdgeKey) {
        if (logicalEdgeKey == null) {
            return null;
        }
        int separator = logicalEdgeKey.indexOf('\u001f');
        return separator < 0 ? null : logicalEdgeKey.substring(0, separator);
    }
}
