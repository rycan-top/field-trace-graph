package io.fieldtracegraph.core.model;

import java.util.List;
import java.util.Locale;

// 作用：索引质量指标聚合，包括精确率、候选率、边界数与原因分布。
// 使用场景：status --quality 输出与 CI 中的边界密度门禁。

/**
 * Product-facing index quality metrics. Ratios are computed over field data
 * edges, while open boundaries come from unresolved call references that
 * require classpath, generated source, or runtime evidence.
 */
public record IndexQuality(
        double exactRatio,
        double candidateRatio,
        double unresolvedRatio,
        int exactEdgeCount,
        int candidateEdgeCount,
        int unresolvedEdgeCount,
        int runtimeObservedEdgeCount,
        int frameworkAdapterEdgeCount,
        double frameworkAdapterRatio,
        int ambiguousCandidateEdgeCount,
        double ambiguousCandidateRatio,
        int openBoundaryCount,
        double openBoundaryDensityPer1000Methods,
        List<BoundaryReasonCount> topBoundaryReasons,
        List<BoundaryReasonCount> boundaryReasons
) {
    // 作用：规范化质量指标中的原因列表；逻辑：空值转为空列表，非空转为不可变副本。
    public IndexQuality {
        topBoundaryReasons = topBoundaryReasons == null
                ? List.of()
                : List.copyOf(topBoundaryReasons);
        boundaryReasons = boundaryReasons == null
                ? List.of()
                : List.copyOf(boundaryReasons);
    }

    // 作用：构造空质量指标；逻辑：所有计数、比例与原因分布都置零或置空。
    public static IndexQuality empty() {
        return new IndexQuality(0d, 0d, 0d, 0, 0, 0, 0, 0, 0d, 0, 0d,
                0, 0d, List.of(), List.of());
    }

    /**
     * Full open-boundary count for one canonical reason code. Legacy aliases
     * (for example {@code EXTERNAL} and {@code EXTERNAL_LIBRARY}) are merged.
     */
    // 作用：按规范原因码统计边界数量；逻辑：遍历原因分布并合并旧别名后累加计数。
    public int boundaryCount(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return 0;
        }
        BoundaryReason wanted = BoundaryReason.from(reasonCode);
        int total = 0;
        for (BoundaryReasonCount count : boundaryReasons) {
            if (BoundaryReason.from(count.reason()) == wanted) {
                total += count.count();
            }
        }
        return total;
    }

    // 作用：列出所有出现过的边界原因名；逻辑：取原因分布中的名称并排序返回。
    public List<String> boundaryReasonNames() {
        return boundaryReasons.stream()
                .map(count -> count.reason().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    public record BoundaryReasonCount(String reason, int count) {
    }
}
