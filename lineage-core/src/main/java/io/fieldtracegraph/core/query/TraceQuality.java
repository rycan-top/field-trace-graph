package io.fieldtracegraph.core.query;

import io.fieldtracegraph.core.model.TraceEdge;
import io.fieldtracegraph.core.model.TraceEdgeCategory;
import io.fieldtracegraph.core.model.TraceResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 作用：计算一次追踪的质量结论。
// 使用场景：输出通过、部分完成、证据不足或未知，并支撑质量门禁。

/**
 * Applies one deterministic quality gate to user-facing trace edges.
 *
 * <p>Only field value edges determine whether field lineage is complete.
 * Call producer edges and explicit dependency edges are reported separately so
 * structural gaps remain visible without being mistaken for missing field
 * value evidence.</p>
 */
public record TraceQuality(
        Status status,
        int unresolvedEdges,
        int candidateEdges,
        int userFacingEdges,
        int valueEdges,
        int valueUnresolvedEdges,
        int structureEdges,
        int structureUnresolvedEdges,
        Status structureStatus,
        int dependencyEdges,
        int dependencyUnresolvedEdges,
        Status dependencyStatus,
        int diagnosticEdges,
        int diagnosticUnresolvedEdges,
        Status diagnosticStatus,
        int openBoundaryCount,
        List<BoundaryReasonCount> boundaryReasons,
        int weakHopCount
) {
    private static final double PARTIAL_THRESHOLD = 0.10d;
    private static final double INSUFFICIENT_THRESHOLD = 0.30d;

    public enum Status {
        OK,
        PARTIAL,
        INSUFFICIENT,
        UNKNOWN
    }

    public record BoundaryReasonCount(String reason, int count) {
    }

    // 作用：从追踪结果推导质量结论；逻辑：统计各类边并套用阈值规则。
    public static TraceQuality from(TraceResult result) {
        return from(result, false, false);
    }

    // 作用：按显式计数构造质量结论；逻辑：根据未解析比例、候选情况与截断状态判定。
    public static TraceQuality from(
            TraceResult result,
            boolean allowCandidate,
            boolean failOnCandidate
    ) {
        EdgeCounts value = count(result, TraceEdgeCategory.FIELD_VALUE);
        EdgeCounts structure = count(result, TraceEdgeCategory.CALL_STRUCTURE);
        EdgeCounts dependency = count(result, TraceEdgeCategory.DEPENDENCY);
        EdgeCounts diagnostic = count(result, TraceEdgeCategory.DIAGNOSTIC);
        double ratio = value.total() == 0 ? 0d : (double) value.unresolved() / value.total();
        Status status;
        boolean candidateBlocks = value.candidate() > 0
                && (!allowCandidate || failOnCandidate);
        if (value.total() == 0) {
            status = Status.UNKNOWN;
        } else if (ratio >= INSUFFICIENT_THRESHOLD) {
            status = Status.INSUFFICIENT;
        } else if (ratio >= PARTIAL_THRESHOLD
                || candidateBlocks
                || (result.truncated() && value.unresolved() > 0)) {
            status = Status.PARTIAL;
        } else {
            status = Status.OK;
        }
        return new TraceQuality(
                status,
                value.unresolved(),
                value.candidate(),
                value.total(),
                value.total(),
                value.unresolved(),
                structure.total(),
                structure.unresolved(),
                supplementaryStatus(structure),
                dependency.total(),
                dependency.unresolved(),
                supplementaryStatus(dependency),
                diagnostic.total(),
                diagnostic.unresolved(),
                supplementaryStatus(diagnostic),
                result.boundaries() == null ? 0 : result.boundaries().size(),
                boundaryReasons(result),
                result.weakHops() == null ? 0 : result.weakHops().size()
        );
    }

    // 作用：判断结果是否部分完成；逻辑：返回状态标记。
    public boolean partial() {
        return status == Status.PARTIAL;
    }

    // 作用：判断证据是否不足；逻辑：返回状态标记。
    public boolean insufficient() {
        return status == Status.INSUFFICIENT;
    }

    // 作用：判断结果是否未知；逻辑：返回状态标记。
    public boolean unknown() {
        return status == Status.UNKNOWN;
    }

    // 作用：返回未解析边比例；逻辑：直接暴露计算后的比例。
    public double unresolvedRatio() {
        return userFacingEdges == 0 ? 0d : (double) unresolvedEdges / userFacingEdges;
    }

    // 作用：生成质量摘要；逻辑：按状态拼接比例、边界与截断信息。
    public String summary() {
        String value = String.format(
                "%s (%d/%d unresolved field-value edges, %.1f%%; %d candidate, %.1f%%)",
                status,
                valueUnresolvedEdges,
                valueEdges,
                unresolvedRatio() * 100d,
                candidateEdges,
                candidateRatio() * 100d
        );
        if (structureEdges == 0 && dependencyEdges == 0 && diagnosticEdges == 0) {
            return value;
        }
        StringBuilder summary = new StringBuilder(value);
        if (structureEdges > 0) {
            summary.append(String.format(
                    "; call structure %s (%d/%d unresolved)",
                    structureStatus,
                    structureUnresolvedEdges,
                    structureEdges
            ));
        }
        if (dependencyEdges > 0) {
            summary.append(String.format(
                    "; dependencies %s (%d/%d unresolved)",
                    dependencyStatus,
                    dependencyUnresolvedEdges,
                    dependencyEdges
            ));
        }
        if (diagnosticEdges > 0) {
            summary.append(String.format(
                    "; diagnostics %s (%d/%d unresolved)",
                    diagnosticStatus,
                    diagnosticUnresolvedEdges,
                    diagnosticEdges
            ));
        }
        if (openBoundaryCount > 0) {
            summary.append("; ").append(openBoundaryCount).append(" open boundaries");
            if (!boundaryReasons.isEmpty()) {
                BoundaryReasonCount top = boundaryReasons.get(0);
                summary.append(" (top ").append(top.reason()).append(' ').append(top.count()).append(')');
            }
        }
        return summary.toString();
    }

    // 作用：汇总边界原因；逻辑：按原因分组计数并排序。
    private static List<BoundaryReasonCount> boundaryReasons(TraceResult result) {
        if (result.boundaries() == null || result.boundaries().isEmpty()) {
            return List.of();
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (var boundary : result.boundaries()) {
            String reason = boundary.reasonCode() == null || boundary.reasonCode().isBlank()
                    ? io.fieldtracegraph.core.model.BoundaryReason.from(boundary.reason()).name()
                    : boundary.reasonCode();
            counts.merge(reason, 1, Integer::sum);
        }
        List<BoundaryReasonCount> resultCounts = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> resultCounts.add(
                        new BoundaryReasonCount(entry.getKey(), entry.getValue())));
        return List.copyOf(resultCounts);
    }

    // 作用：判断结构是否部分；逻辑：存在截断或环时返回 true。
    public boolean structurePartial() {
        return structureStatus == Status.PARTIAL || structureStatus == Status.INSUFFICIENT;
    }

    // 作用：统计某类边的数量；逻辑：遍历边并按解析强度分类计数。
    private static EdgeCounts count(TraceResult result, TraceEdgeCategory category) {
        int total = 0;
        int unresolved = 0;
        int candidate = 0;
        for (TraceEdge edge : result.edges()) {
            TraceEdgeCategory edgeCategory = edge.category() == null
                    ? TraceEdgeCategory.fromEdgeType(edge.edgeType())
                    : edge.category();
            if (edgeCategory != category) {
                continue;
            }
            total++;
            if (unresolved(edge)) {
                unresolved++;
            }
            if (candidate(edge)) {
                candidate++;
            }
        }
        return new EdgeCounts(total, unresolved, candidate);
    }

    // 作用：计算补充边的状态；逻辑：按未解析与候选比例判定。
    private static Status supplementaryStatus(EdgeCounts counts) {
        if (counts.total() == 0 || counts.unresolved() == 0) {
            return Status.OK;
        }
        return Status.PARTIAL;
    }

    // 作用：判断边是否未解析；逻辑：解析强度为 UNRESOLVED 时返回 true。
    private static boolean unresolved(TraceEdge edge) {
        if (edge.resolution() == null) {
            return !edge.resolved() && !edge.bridged();
        }
        return "UNRESOLVED".equals(edge.resolution());
    }

    // 作用：判断边是否为候选；逻辑：解析强度为 CANDIDATE 时返回 true。
    private static boolean candidate(TraceEdge edge) {
        return "CANDIDATE".equals(edge.resolution());
    }

    // 作用：返回候选边比例；逻辑：直接暴露计算后的比例。
    public double candidateRatio() {
        return userFacingEdges == 0 ? 0d : (double) candidateEdges / userFacingEdges;
    }

    private record EdgeCounts(int total, int unresolved, int candidate) {
    }
}
