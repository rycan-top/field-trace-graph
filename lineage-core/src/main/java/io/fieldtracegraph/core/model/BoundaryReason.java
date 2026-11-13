package io.fieldtracegraph.core.model;

import java.util.Locale;

// 作用：开放边界的原因码枚举。
// 使用场景：边界统计、按原因做发布门禁、JSON 输出中的结构化原因。

/**
 * Canonical boundary reason codes. Every unresolved reference and every
 * user-facing boundary carries one of these values so quality gates, reports
 * and adapters can agree on the same vocabulary.
 *
 * <p>{@code RPC_SERIALIZATION} is kept as a legacy alias for indexes produced
 * before RPC and serialization boundaries were split. New analyses emit
 * {@link #RPC_BOUNDARY} or {@link #SERIALIZATION_BOUNDARY}.</p>
 *
 */
public enum BoundaryReason {
    PLATFORM_LIBRARY(false, "none"),
    EXTERNAL_LIBRARY(true, "provide-classpath-or-runtime-trace"),
    CLASSPATH_MISSING(true, "provide-classpath"),
    CALLEE_SYMBOL_MISSING(true, "reindex-with-generated-sources-or-provide-classpath"),
    GENERATED_CODE_MISSING(true, "index-generated-sources-or-provide-classpath"),
    RECEIVER_TYPE_UNKNOWN(true, "trace-receiver-expression-or-import-runtime-trace"),
    OVERLOAD_AMBIGUOUS(true, "disambiguate-overload-or-provide-classpath"),
    VIRTUAL_AMBIGUOUS(true, "provide-classpath-or-runtime-trace"),
    USE_UNRESOLVED(true, "resolve-call-target-or-provide-classpath"),
    CONSUMED_BY_CALL(false, "none"),
    NO_CONSUMER(false, "none"),
    NO_USE(false, "none"),
    RPC_BOUNDARY(true, "import-runtime-trace-or-provide-contract"),
    SERIALIZATION_BOUNDARY(true, "import-runtime-trace-or-provide-contract"),
    /** @deprecated split into {@link #RPC_BOUNDARY} and {@link #SERIALIZATION_BOUNDARY}. */
    @Deprecated
    RPC_SERIALIZATION(true, "import-runtime-trace-or-provide-contract"),
    DYNAMIC_SQL(true, "import-runtime-sql"),
    DYNAMIC_PROXY(true, "import-runtime-trace"),
    AOP(true, "import-runtime-trace"),
    REFLECTION(true, "import-runtime-trace"),
    UNKNOWN_BOUNDARY(true, "inspect-boundary-evidence");

    private final boolean recoverable;
    private final String suggestedAction;

    // 作用：保存边界原因的可恢复性与处置建议；逻辑：直接记录构造参数供查询方法返回。
    BoundaryReason(boolean recoverable, String suggestedAction) {
        this.recoverable = recoverable;
        this.suggestedAction = suggestedAction;
    }

    // 作用：判断该边界能否通过补证据解决；逻辑：直接返回构造时记录的标记。
    public boolean recoverable() {
        return recoverable;
    }

    // 作用：返回该边界建议的下一步动作；逻辑：直接返回构造时记录的描述。
    public String suggestedAction() {
        return suggestedAction;
    }

    /**
     * Resolves a stored reason string. Unknown values map to
     * {@link #UNKNOWN_BOUNDARY} so new producer output never breaks readers.
     */
    // 作用：把存储的原因字符串解析成枚举；逻辑：空值返回 UNKNOWN，先归一化大小写与分隔符，再兼容旧别名，最后按名称匹配。
    public static BoundaryReason from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN_BOUNDARY;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (value) {
            case "EXTERNAL", "EXTERNAL_BOUNDARY" -> EXTERNAL_LIBRARY;
            case "AMBIGUOUS_OVERLOAD" -> OVERLOAD_AMBIGUOUS;
            case "SQL_PARAMETER" -> DYNAMIC_SQL;
            case "NO_SOURCE" -> GENERATED_CODE_MISSING;
            default -> {
                for (BoundaryReason reason : values()) {
                    if (reason.name().equals(value)) {
                        yield reason;
                    }
                }
                yield UNKNOWN_BOUNDARY;
            }
        };
    }

    // 作用：返回原因码的规范名称；逻辑：复用 from 解析后取枚举名，保证新旧别名落到同一名称。
    public static String canonicalName(String raw) {
        return from(raw).name();
    }

    /** True for reasons that still describe an open, actionable boundary. */
    // 作用：判断原因是否仍属于开放边界；逻辑：平台库边界视为已关闭，其余原因都算开放。
    public static boolean isOpen(String raw) {
        return from(raw) != PLATFORM_LIBRARY;
    }
}
