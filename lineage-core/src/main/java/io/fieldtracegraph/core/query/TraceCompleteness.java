package io.fieldtracegraph.core.query;

import io.fieldtracegraph.core.model.TraceBoundary;
import io.fieldtracegraph.core.model.TraceResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 作用：计算面向用户的完整性结论。
// 使用场景：区分“字段值边已解析”与“整条使用链已经连通”。

/**
 * A separate presentation-level completeness verdict.
 *
 * <p>{@link TraceQuality} answers whether the emitted field-value edges are
 * resolved. Completeness answers the stronger question a reader actually asks:
 * can every consumer of the selected value be followed? Weak hops and
 * {@code USE_UNRESOLVED} boundaries describe a consumer that exists but could
 * not be bridged, so they downgrade completeness even though the emitted edges
 * themselves are exact.</p>
 */
public record TraceCompleteness(
        Status status,
        List<Reason> reasons
) {
    public enum Status {
        COMPLETE,
        PARTIAL,
        INSUFFICIENT,
        UNKNOWN,
        INCOMPLETE
    }

    // 作用：记录完整性降级原因；逻辑：保存稳定代码与可读说明。
    public record Reason(String code, String message) {
    }

    // 作用：从追踪结果推导完整性；逻辑：按未知、过期、证据不足、部分、完整的优先级聚合原因。
    public static TraceCompleteness from(TraceResult result) {
        TraceQuality quality = TraceQuality.from(
                result,
                result.allowCandidate(),
                result.failOnCandidate()
        );
        List<Reason> reasons = new ArrayList<>();
        if (quality.status() == TraceQuality.Status.DEPENDENCY_ONLY
                && quality.weakHopCount() == 0
                && !result.truncated()) {
            return new TraceCompleteness(
                    Status.COMPLETE,
                    List.of(new Reason(
                            "CONDITION_ONLY",
                            "the target is consumed only through condition/control dependencies"
                    ))
            );
        }
        /*
         * Condition-only consumption stays condition-only when some unrelated
         * consumer in the same cone cannot be bridged. Without this guard the
         * empty edge list falls through to UNKNOWN, which reads as "we could
         * not find anything" even though the control dependency is exact.
         */
        if (result.edges().isEmpty() && consumesOnlyConditions(result)) {
            reasons.add(new Reason(
                    "CONDITION_ONLY",
                    "the target is consumed only through condition/control dependencies"
            ));
            if (result.truncated()) {
                reasons.add(new Reason(
                        "TRUNCATED",
                        "traversal stopped early: " + String.join(",", result.truncationReasons())
                ));
            }
            if (quality.weakHopCount() > 0) {
                reasons.add(new Reason(
                        "WEAK_HOPS",
                        quality.weakHopCount()
                                + " cross-call hops remain unbridged (including external/platform boundaries)"
                ));
            }
            boolean partial = quality.weakHopCount() > 0 || result.truncated();
            return new TraceCompleteness(partial ? Status.PARTIAL : Status.COMPLETE, List.copyOf(reasons));
        }
        if (quality.unknown() || result.edges().isEmpty()) {
            if (result.edges().isEmpty() && externalAccessorBoundary(result)) {
                /*
                 * The accessor exists in the source, so the field is not
                 * "missing evidence"; the dependency metadata needed to prove
                 * and follow it is missing. PARTIAL is the honest verdict.
                 */
                reasons.add(new Reason(
                        "EXTERNAL_ACCESSOR_UNVERIFIED",
                        "the field is read through an accessor whose receiver type is outside "
                                + "the analysed sources; provide --classpath-file to prove it"
                ));
                return new TraceCompleteness(Status.PARTIAL, List.copyOf(reasons));
            }
            reasons.add(new Reason("NO_FIELD_EVIDENCE", "no field-value evidence was produced"));
            return new TraceCompleteness(Status.UNKNOWN, List.copyOf(reasons));
        }
        if ("STALE".equals(result.freshness())) {
            reasons.add(new Reason("INDEX_STALE", "the published index no longer matches the source tree"));
        }
        if (!result.snapshotConsistent()) {
            reasons.add(new Reason("SNAPSHOT_INCONSISTENT", "evidence comes from more than one index generation"));
        }
        if (result.truncated()) {
            reasons.add(new Reason(
                    "TRUNCATED",
                    "traversal stopped early: " + String.join(",", result.truncationReasons())
            ));
        }
        if (quality.valueUnresolvedEdges() > 0) {
            reasons.add(new Reason(
                    "UNRESOLVED_FIELD_EDGES",
                    quality.valueUnresolvedEdges() + " of " + quality.valueEdges()
                            + " field-value edges are unresolved"
            ));
        }
        boolean candidateBlocks = quality.candidateEdges() > 0
                && (!result.allowCandidate() || result.failOnCandidate());
        if (candidateBlocks) {
            reasons.add(new Reason(
                    "CANDIDATE_EDGES",
                    quality.candidateEdges() + " field-value edges are candidate evidence"
            ));
        }
        if (quality.weakHopCount() > 0) {
            reasons.add(new Reason(
                    "WEAK_HOPS",
                    quality.weakHopCount()
                            + " cross-call hops remain unbridged (including external/platform boundaries)"
            ));
        }
        int unbridged = unbridgedConsumers(result);
        if (unbridged > 0) {
            reasons.add(new Reason(
                    "UNBRIDGED_CONSUMERS",
                    unbridged + " visible consumers have no bridged callee body"
            ));
        }
        if (reasons.isEmpty()) {
            return new TraceCompleteness(Status.COMPLETE, List.of());
        }
        Status status = quality.insufficient()
                ? Status.INSUFFICIENT
                : reasons.stream().anyMatch(reason -> "INDEX_STALE".equals(reason.code()))
                ? Status.INCOMPLETE
                : Status.PARTIAL;
        return new TraceCompleteness(status, List.copyOf(reasons));
    }

    // 作用：统计未桥接消费者边界；逻辑：按原因代码计不同值节点，避免重复计数。
    /*
     * True when the only consumption evidence for the value is a control
     * dependency. A value that is also passed into an unbridged callee keeps
     * the weaker UNKNOWN/PARTIAL treatment instead of being labelled as
     * condition-only, because that consumer could carry the value onward.
     */
    // 作用：判断目标是否只被条件语句消费；逻辑：必须存在 CONDITION_USES 且没有未桥接消费者。
    private static boolean consumesOnlyConditions(TraceResult result) {
        if (result.boundaries() == null) {
            return false;
        }
        boolean condition = false;
        for (TraceBoundary boundary : result.boundaries()) {
            String code = boundary.reasonCode();
            if ("USE_UNRESOLVED".equals(code) || "USE_NOT_BRIDGED".equals(code)
                    || "CONSUMED_BY_CALL".equals(code)) {
                return false;
            }
            condition |= "CONDITION_USES".equals(boundary.reason());
        }
        return condition;
    }

    // 作用：判断是否存在外部访问器边界；逻辑：只认字段节点上的缺 classpath 或外部库原因。
    private static boolean externalAccessorBoundary(TraceResult result) {
        if (result.boundaries() == null) {
            return false;
        }
        return result.boundaries().stream().anyMatch(boundary ->
                "FIELD".equals(boundary.kind())
                        && ("CLASSPATH_MISSING".equals(boundary.reasonCode())
                        || "EXTERNAL_LIBRARY".equals(boundary.reasonCode())));
    }

    private static int unbridgedConsumers(TraceResult result) {
        Map<String, Boolean> nodes = new LinkedHashMap<>();
        for (TraceBoundary boundary : result.boundaries()) {
            String code = boundary.reasonCode();
            boolean unbridged = "USE_UNRESOLVED".equals(code)
                    || "USE_NOT_BRIDGED".equals(code)
                    || "CONSUMED_BY_CALL".equals(code)
                    || (code == null && "USE_UNRESOLVED".equals(boundary.reason()));
            if (unbridged) {
                nodes.putIfAbsent(boundary.nodeId(), Boolean.TRUE);
            }
        }
        return nodes.size();
    }

    // 作用：判断是否完整；逻辑：仅 COMPLETE 为真。
    public boolean complete() {
        return status == Status.COMPLETE;
    }

    // 作用：生成单行摘要；逻辑：状态后附前三个原因，便于 text/JSON 共用。
    public String summary() {
        if (reasons.isEmpty()) {
            return status.name();
        }
        String detail = reasons.stream()
                .limit(3)
                .map(Reason::message)
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
        return status.name() + " (" + detail + ")";
    }
}
