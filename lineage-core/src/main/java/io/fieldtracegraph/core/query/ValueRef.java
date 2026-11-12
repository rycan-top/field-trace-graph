package io.fieldtracegraph.core.query;

import java.util.List;

// 作用：值引用标识。
// 使用场景：在遍历中唯一指向字段、参数或表达式节点。

/**
 * A value node identity plus an optional field access path. Field paths are
 * kept separate from the node identity so distinct object instances do not
 * merge merely because they share a type and field name.
 */
record ValueRef(String nodeId, List<String> fields, String callContext) {
    private static final String CALL_CONTEXT_MARKER = "|call-context=";

    // 作用：构造值引用；逻辑：保存节点 ID 与字段路径，空路径转为空列表。
    ValueRef(String nodeId, List<String> fields) {
        this(nodeId, fields, "");
    }

    // 作用：生成引用键；逻辑：把节点 ID 与字段路径拼接，用于遍历去重。
    String key() {
        String suffix = ValuePath.suffix(fields);
        String value = fields.isEmpty()
                ? nodeId
                : nodeId + (suffix.startsWith("[") ? "" : ".") + suffix;
        return callContext.isBlank() ? value : value + CALL_CONTEXT_MARKER + callContext;
    }

    /**
     * Call context prevents a shared callee value from being attributed to the
     * wrong caller. It is traversal state, not part of the user-visible value
     * identity, so rendered nodes and physical evidence edges use the key
     * without the context suffix.
     */
    // 作用：去掉引用键中的调用上下文；逻辑：截断上下文分隔符之后的部分。
    static String withoutCallContext(String key) {
        if (key == null) {
            return null;
        }
        int marker = key.indexOf(CALL_CONTEXT_MARKER);
        return marker < 0 ? key : key.substring(0, marker);
    }
}
