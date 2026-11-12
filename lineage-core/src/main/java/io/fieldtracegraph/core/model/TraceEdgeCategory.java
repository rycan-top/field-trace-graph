package io.fieldtracegraph.core.model;

// 作用：结果边分类。
// 使用场景：区分字段值流、控制依赖与调用输入，避免把依赖边误当血缘。

/**
 * Quality and presentation role of a trace edge.
 *
 * <p>Call producer hops explain how a call result is formed, but they are not
 * field value lineage themselves. Keeping this distinction lets the quality
 * gate report structural gaps without incorrectly degrading field lineage.</p>
 *
 * 作用：边分类；场景：区分字段流、控制依赖、调用输入。
 */
public enum TraceEdgeCategory {
    FIELD_VALUE,
    CALL_STRUCTURE,
    DEPENDENCY,
    DIAGNOSTIC;

    // 作用：把边类型映射为展示分类；逻辑：按类型名前缀归类，未识别时归为其它。
    public static TraceEdgeCategory fromEdgeType(String edgeType) {
        if (edgeType == null) {
            return FIELD_VALUE;
        }
        return switch (edgeType) {
            case "CALL_PRODUCER" -> CALL_STRUCTURE;
            case "CALL_INPUT_USES", "CONDITION_USES", "MUTATES_PARAMETER" -> DEPENDENCY;
            case "EXTERNAL_BOUNDARY", "UNRESOLVED_FIELD_SOURCE",
                    "OBJECT_FIELD", "FIELD_OWNER_SOURCE" -> DIAGNOSTIC;
            default -> FIELD_VALUE;
        };
    }
}
