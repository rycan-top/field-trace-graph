package io.fieldtracegraph.core.query;

import java.util.Locale;

// 作用：调用范围模式枚举。
// 使用场景：trace --call-scope effective/full 控制是否包含调用方。

/**
 * Controls how much of the call graph is loaded for one trace request.
 */
public enum CallScopeMode {
    /**
     * Resolve callers per traversal direction. Backward walks include callers
     * so a field can reach its upstream producer, while forward walks stay in
     * the selected method and its callees to avoid pulling in every caller's
     * downstream use sites.
     */
    AUTO(false),

    /**
     * Follow the selected method and its callees. This is the default because
     * a field query should not pull in every unrelated caller of the method.
     */
    EFFECTIVE(false),

    /**
     * Follow callers and callees. This is an explicit diagnostic mode for
     * requests that need to inspect how a field reaches invoking code.
     */
    FULL(true);

    private final boolean includesCallers;

    // 作用：记录该模式是否包含调用方；逻辑：保存 includesCallers 标记。
    CallScopeMode(boolean includesCallers) {
        this.includesCallers = includesCallers;
    }

    // 作用：判断当前模式是否包含调用方；逻辑：直接返回保存的标记。
    public boolean includesCallers() {
        return includesCallers;
    }

    // 作用：把命令行值解析为调用范围模式；逻辑：忽略大小写匹配 effective/full，缺省回退 AUTO。
    public static CallScopeMode fromCliValue(String value) {
        if (value == null || value.isBlank()) {
            return EFFECTIVE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "call scope must be one of: auto, effective, full",
                    exception
            );
        }
    }
}
