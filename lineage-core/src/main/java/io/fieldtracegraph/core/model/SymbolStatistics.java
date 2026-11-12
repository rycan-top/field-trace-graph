package io.fieldtracegraph.core.model;

// 作用：符号维度统计。
// 使用场景：status 展示类、方法、字段等数量分布。

/**
 * Canonical symbol counters used when comparing the local graph with another
 * code-graph implementation. Type counts and callable roles are deliberately
 * separate so a getter is never counted as business logic or as a type.
 */
public record SymbolStatistics(
        int totalCount,
        int typeCount,
        int methodCount,
        int businessMethodCount,
        int accessorCount,
        int constructorCount,
        int initializerCount
) {
    // 作用：构造空符号统计；逻辑：所有计数置零。
    public static SymbolStatistics empty() {
        return new SymbolStatistics(0, 0, 0, 0, 0, 0, 0);
    }
}
