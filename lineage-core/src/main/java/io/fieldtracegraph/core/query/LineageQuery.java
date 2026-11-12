package io.fieldtracegraph.core.query;

import java.util.Set;

// 作用：血缘查询的参数对象。
// 使用场景：承载方向、范围、预算与目标表达式等查询条件。

/**
 * Normalized query parameters and budget for a single lineage request. It is
 * validated before any index write so invalid values fail fast.
 */
public record LineageQuery(String direction, int maxDepth, int maxEdges, long timeoutMillis) {
    private static final Set<String> DIRECTIONS = Set.of("in", "out", "both");

    // 作用：按方向与预算构造查询；逻辑：规范方向字符串并保存预算对象。
    public static LineageQuery of(String direction, TraversalBudget budget) {
        if (direction == null || !DIRECTIONS.contains(direction)) {
            throw new IllegalArgumentException("direction must be one of: in, out, both");
        }
        return new LineageQuery(
                direction,
                budget.maxDepth(),
                budget.maxEdges(),
                budget.timeoutMillis()
        );
    }

    // 作用：返回查询使用的遍历预算；逻辑：直接暴露构造时保存的预算。
    public TraversalBudget budget() {
        return new TraversalBudget(maxDepth, maxEdges, timeoutMillis);
    }
}
