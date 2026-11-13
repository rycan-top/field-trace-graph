package io.fieldtracegraph.cli.bootstrap;

import java.io.InputStream;
import java.util.Properties;

// 作用：读取构建期写入的 CLI 版本。
// 使用场景：--version 与根命令输出，避免在 Java 源码中硬编码版本号。

/**
 * Reads the version injected by Maven resource filtering. The checked-in
 * placeholder is replaced at build time, so bumping the root POM revision is
 * enough to keep the CLI banner in sync with the release.
 */
public final class VersionInfo {
    private static final String RESOURCE = "/field-trace-graph-version.properties";
    private static final String FALLBACK = "development";

    private VersionInfo() {
    }

    // 作用：返回构建版本；逻辑：读取过滤后的属性文件，缺失时返回开发占位值。
    public static String version() {
        try (InputStream stream = VersionInfo.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return FALLBACK;
            }
            Properties properties = new Properties();
            properties.load(stream);
            String version = properties.getProperty("version", "").trim();
            if (version.isEmpty() || version.contains("${")) {
                return FALLBACK;
            }
            return version;
        } catch (Exception ignored) {
            return FALLBACK;
        }
    }
}
