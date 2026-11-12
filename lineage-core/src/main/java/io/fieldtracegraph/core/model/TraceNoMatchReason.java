package io.fieldtracegraph.core.model;

// 作用：未命中目标时的原因说明。
// 使用场景：目标不存在或无法解析时给出可行动解释，而不是空结果。

/**
 * Structured reason for a resolved target that has no lineage edge. It is
 * deliberately separate from a missing target: the latter is an input error,
 * while this is a valid graph result.
 *
 * 作用：未命中原因；场景：目标不存在时给出可行动解释。
 */
public record TraceNoMatchReason(String code, String message) {
}
