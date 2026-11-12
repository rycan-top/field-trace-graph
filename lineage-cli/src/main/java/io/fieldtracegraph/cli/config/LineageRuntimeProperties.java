package io.fieldtracegraph.cli.config;

// 作用：记录运行时参数。
// 使用场景：传递遍历深度、预算、超时等默认值与上限。

/**
 * Immutable runtime limits and defaults for one CLI invocation. Values are
 * resolved by {@link ToolConfigurationLoader}; services read them instead of
 * hard-coding budgets so behaviour stays consistent across commands.
 */
public record LineageRuntimeProperties(
        String parserVersion,
        int maxSourceFiles,
        long maxSourceFileBytes,
        long maxTotalSourceBytes,
        int maxSourceDepth,
        long lockTimeoutMillis,
        long indexTimeoutMillis,
        long queryTimeoutMillis,
        int maxQueryDepth,
        int maxQueryEdges,
        int maxCalleeDepth
) {
    // 作用：构造出厂默认配置；逻辑：返回解析器版本、扫描预算、锁与查询超时及深度上限。
    public static LineageRuntimeProperties defaults() {
        return new LineageRuntimeProperties(
                "0.3.3",
                10_000,
                2L * 1024 * 1024,
                200L * 1024 * 1024,
                64,
                0L,
                300_000L,
                0L,
                100,
                5_000,
                5
        );
    }

    // 作用：替换解析器版本；逻辑：复制其余字段并生成新的不可变配置。
    public LineageRuntimeProperties withParserVersion(String newParserVersion) {
        return new LineageRuntimeProperties(
                newParserVersion,
                maxSourceFiles,
                maxSourceFileBytes,
                maxTotalSourceBytes,
                maxSourceDepth,
                lockTimeoutMillis,
                indexTimeoutMillis,
                queryTimeoutMillis,
                maxQueryDepth,
                maxQueryEdges,
                maxCalleeDepth
        );
    }
}
