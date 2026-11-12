package io.fieldtracegraph.cli.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

// 作用：读取 properties、环境变量与系统属性。
// 使用场景：在不改代码的情况下覆盖出厂默认配置。

/**
 * Loads tool configuration from the built-in resource and a small whitelist of
 * environment variables. It never searches the analysed project for
 * application.yml or application.properties.
 */
public final class ToolConfigurationLoader {
    private static final String RESOURCE = "field-trace-graph.properties";

    private static final Map<String, String> ENV_OVERRIDES = Map.ofEntries(
            Map.entry("FIELD_TRACE_GRAPH_PARSER_VERSION", "field-trace-graph.parser-version"),
            Map.entry("FIELD_TRACE_GRAPH_SOURCE_MAX_FILES", "field-trace-graph.source.max-files"),
            Map.entry("FIELD_TRACE_GRAPH_SOURCE_MAX_FILE_BYTES", "field-trace-graph.source.max-file-bytes"),
            Map.entry("FIELD_TRACE_GRAPH_SOURCE_MAX_TOTAL_BYTES", "field-trace-graph.source.max-total-bytes"),
            Map.entry("FIELD_TRACE_GRAPH_SOURCE_MAX_DEPTH", "field-trace-graph.source.max-depth"),
            Map.entry("FIELD_TRACE_GRAPH_INDEX_LOCK_TIMEOUT_MILLIS", "field-trace-graph.index.lock-timeout-millis"),
            Map.entry("FIELD_TRACE_GRAPH_INDEX_TIMEOUT_MILLIS", "field-trace-graph.index.timeout-millis"),
            Map.entry("FIELD_TRACE_GRAPH_QUERY_TIMEOUT_MILLIS", "field-trace-graph.query.timeout-millis"),
            Map.entry("FIELD_TRACE_GRAPH_QUERY_MAX_DEPTH", "field-trace-graph.query.max-depth"),
            Map.entry("FIELD_TRACE_GRAPH_QUERY_MAX_EDGES", "field-trace-graph.query.max-edges"),
            Map.entry("FIELD_TRACE_GRAPH_QUERY_MAX_CALLEE_DEPTH", "field-trace-graph.query.max-callee-depth")
    );

    // 作用：加载运行配置；逻辑：先读内置资源，再用环境变量覆盖，最后转换为配置对象。
    public LineageRuntimeProperties load() {
        Properties properties = readResource();
        applyEnvironment(properties);
        return fromProperties(properties);
    }

    // 作用：读取内置配置文件；逻辑：从类路径加载 properties，缺失时返回空配置。
    private Properties readResource() {
        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read tool configuration resource", e);
        }
        return properties;
    }

    // 作用：应用环境变量覆盖；逻辑：按白名单逐项读取非空环境变量并写入配置键。
    private void applyEnvironment(Properties properties) {
        for (var entry : ENV_OVERRIDES.entrySet()) {
            String value = System.getenv(entry.getKey());
            if (value != null && !value.isBlank()) {
                properties.setProperty(entry.getValue(), value.trim());
            }
        }
    }

    // 作用：把配置项转换为运行参数；逻辑：逐键读取并提供默认值兜底。
    private LineageRuntimeProperties fromProperties(Properties properties) {
        LineageRuntimeProperties defaults = LineageRuntimeProperties.defaults();
        return new LineageRuntimeProperties(
                property(properties, "field-trace-graph.parser-version", defaults.parserVersion()),
                intProperty(properties, "field-trace-graph.source.max-files", defaults.maxSourceFiles()),
                longProperty(properties, "field-trace-graph.source.max-file-bytes", defaults.maxSourceFileBytes()),
                longProperty(properties, "field-trace-graph.source.max-total-bytes", defaults.maxTotalSourceBytes()),
                intProperty(properties, "field-trace-graph.source.max-depth", defaults.maxSourceDepth()),
                longProperty(properties, "field-trace-graph.index.lock-timeout-millis", defaults.lockTimeoutMillis()),
                longProperty(properties, "field-trace-graph.index.timeout-millis", defaults.indexTimeoutMillis()),
                longProperty(properties, "field-trace-graph.query.timeout-millis", defaults.queryTimeoutMillis()),
                intProperty(properties, "field-trace-graph.query.max-depth", defaults.maxQueryDepth()),
                intProperty(properties, "field-trace-graph.query.max-edges", defaults.maxQueryEdges()),
                intProperty(properties, "field-trace-graph.query.max-callee-depth", defaults.maxCalleeDepth())
        );
    }

    // 作用：读取字符串配置；逻辑：缺失时返回兜底值。
    private String property(Properties properties, String key, String fallback) {
        return properties.getProperty(key, fallback);
    }

    // 作用：读取整数配置；逻辑：缺失时返回兜底值，否则解析为整数。
    private int intProperty(Properties properties, String key, int fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Integer.parseInt(value);
    }

    // 作用：读取长整数配置；逻辑：缺失时返回兜底值，否则解析为长整数。
    private long longProperty(Properties properties, String key, long fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Long.parseLong(value);
    }
}
