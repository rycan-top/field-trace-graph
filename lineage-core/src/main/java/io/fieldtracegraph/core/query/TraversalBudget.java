package io.fieldtracegraph.core.query;

// 作用：遍历预算的计数器与限制。
// 使用场景：控制深度、边数与超时，触发明确的截断标记。

/**
 * Shared resource budget for a single lineage traversal. The deadline is
 * derived from the timeout so the tracer can stop early without a wall-clock
 * dependency in the domain model.
 */
public record TraversalBudget(int maxDepth, int maxEdges, long timeoutMillis) {
    /**
     * Defaults are intentionally large enough for object-level queries. A
     * field query normally stops much earlier, while an object root can
     * legitimately span several callers and container levels.
     */
    public static final int DEFAULT_MAX_DEPTH = 100;
    public static final int DEFAULT_MAX_EDGES = 5000;
    public static final long DEFAULT_TIMEOUT_MILLIS = 0L;

    // 作用：校验遍历预算范围；逻辑：限制深度与边数上限，并拒绝负的超时时间。
    public TraversalBudget {
        if (maxDepth < 1 || maxDepth > 100) {
            throw new IllegalArgumentException("max-depth must be between 1 and 100");
        }
        if (maxEdges < 1 || maxEdges > 10_000) {
            throw new IllegalArgumentException("max-edges must be between 1 and 10000");
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("query-timeout must not be negative");
        }
    }

    // 作用：计算遍历截止时间；逻辑：零超时表示不限时，否则把毫秒超时加到当前纳秒时间。
    public long deadlineNanos() {
        if (timeoutMillis == 0) {
            return Long.MAX_VALUE;
        }
        return System.nanoTime() + timeoutMillis * 1_000_000L;
    }

    // 作用：判断遍历是否超时；逻辑：当前纳秒时间超过截止时间即返回 true。
    public boolean expired(long deadlineNanos) {
        return System.nanoTime() > deadlineNanos;
    }
}
