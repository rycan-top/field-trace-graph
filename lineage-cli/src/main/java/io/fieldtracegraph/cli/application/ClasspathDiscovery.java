package io.fieldtracegraph.cli.application;

import io.fieldtracegraph.cli.project.ProjectIndexPaths;
import io.fieldtracegraph.cli.log.CliLog;
import io.fieldtracegraph.core.model.SourceScanOptions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the implicit classpath file used by every read and write command.
 *
 * <p>The index freshness check hashes the analysis context, which includes the
 * classpath. If only the indexer discovered {@code classpath.txt}, every later
 * {@code status} or {@code --no-sync} query saw a different context and
 * declared a freshly built index stale. Discovery therefore has to be shared
 * by the indexer and the readers.</p>
 */
// 作用：发现项目默认 classpath 文件。
// 使用场景：索引与状态检查共用同一份分析上下文，避免误判新鲜度。
final class ClasspathDiscovery {
    private static final Set<Path> LOGGED = ConcurrentHashMap.newKeySet();

    // 作用：阻止工具类被实例化；逻辑：私有空构造器。
    private ClasspathDiscovery() {
    }

    // 作用：补全缺省类路径；逻辑：未显式指定时读取工具目录下的 classpath.txt 并跳过失效条目。
    static SourceScanOptions discover(Path root, SourceScanOptions options) {
        if (!options.classpathEntries().isEmpty()) {
            return options;
        }
        Path discovered = ProjectIndexPaths.toolDirectory(root).resolve("classpath.txt");
        if (!Files.isRegularFile(discovered)) {
            return options;
        }
        try {
            List<String> entries = new ArrayList<>();
            int dropped = 0;
            for (String line : Files.readAllLines(discovered, StandardCharsets.UTF_8)) {
                String value = line.strip();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                for (String entry : value.split(File.pathSeparator)) {
                    String candidate = entry.strip();
                    if (candidate.isEmpty()) {
                        continue;
                    }
                    // A stale classpath file (cleaned ~/.m2) must not break the
                    // whole index; drop missing entries and say so.
                    if (!Files.exists(Path.of(candidate))) {
                        dropped++;
                        continue;
                    }
                    entries.add(candidate);
                }
            }
            if (entries.isEmpty()) {
                if (dropped > 0 && LOGGED.add(discovered)) {
                    CliLog.warn("classpath: all " + dropped + " entries in "
                            + discovered + " no longer exist; ignoring the file");
                }
                return options;
            }
            if (LOGGED.add(discovered)) {
                CliLog.info("classpath: using " + entries.size() + " entries from " + discovered
                        + (dropped > 0 ? " (" + dropped + " missing entries skipped)" : ""));
            }
            return new SourceScanOptions(
                    options.languageLevelOverride(),
                    options.includeTests(),
                    options.generatedSourceRoots(),
                    entries
            );
        } catch (IOException e) {
            if (LOGGED.add(discovered)) {
                CliLog.warn("classpath: unable to read " + discovered + ": " + e.getMessage());
            }
            return options;
        }
    }
}
