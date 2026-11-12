package io.fieldtracegraph.cli.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

// 作用：解析并匹配 .gitignore 规则。
// 使用场景：扫描源码时跳过被版本控制忽略的文件。

/**
 * Reads a small, safe subset of .gitignore from the project root. It only
 * supports directory and glob exclusions; negation and nested ignore files are
 * intentionally out of scope so behaviour stays predictable.
 */
public final class GitIgnoreRules {
    private final List<PathMatcher> directoryMatchers;
    private final List<PathMatcher> fileMatchers;

    // 作用：保存编译后的忽略规则；逻辑：分别持有目录与文件匹配器列表。
    private GitIgnoreRules(List<PathMatcher> directoryMatchers, List<PathMatcher> fileMatchers) {
        this.directoryMatchers = directoryMatchers;
        this.fileMatchers = fileMatchers;
    }

    // 作用：读取并编译忽略规则；逻辑：解析 .gitignore 的目录与通配模式，跳过注释、空行与否定规则。
    public static GitIgnoreRules load(Path projectRoot) {
        Path ignoreFile = projectRoot.resolve(".gitignore");
        if (!Files.isRegularFile(ignoreFile)) {
            return new GitIgnoreRules(List.of(), List.of());
        }
        List<PathMatcher> directories = new ArrayList<>();
        List<PathMatcher> files = new ArrayList<>();
        try {
            for (String raw : Files.readAllLines(ignoreFile, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                    continue;
                }
                boolean directoryOnly = line.endsWith("/");
                String pattern = directoryOnly ? line.substring(0, line.length() - 1) : line;
                String glob = pattern.startsWith("/") ? pattern : "**/" + pattern;
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
                if (directoryOnly) {
                    directories.add(matcher);
                } else {
                    files.add(matcher);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read .gitignore", e);
        }
        return new GitIgnoreRules(List.copyOf(directories), List.copyOf(files));
    }

    // 作用：判断路径是否被忽略；逻辑：先按目录匹配器检查自身与祖先目录，再按文件匹配器匹配。
    public boolean isIgnored(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        Path path = Path.of(normalized);
        for (PathMatcher matcher : directoryMatchers) {
            if (matcher.matches(path) || matchesPrefix(matcher, path)) {
                return true;
            }
        }
        for (PathMatcher matcher : fileMatchers) {
            if (matcher.matches(path)) {
                return true;
            }
        }
        return false;
    }

    // 作用：检查祖先目录是否命中规则；逻辑：从父目录逐级向上匹配直到根。
    private boolean matchesPrefix(PathMatcher matcher, Path path) {
        Path current = path.getParent();
        while (current != null) {
            if (matcher.matches(current)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }
}
