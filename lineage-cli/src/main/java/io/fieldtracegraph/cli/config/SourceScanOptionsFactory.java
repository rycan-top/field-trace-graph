package io.fieldtracegraph.cli.config;

import io.fieldtracegraph.core.model.SourceScanOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

// 作用：把命令行参数转换为统一的源码扫描选项。
// 使用场景：index、trace、targets、methods 等命令共用。

/**
 * Builds {@link SourceScanOptions} from CLI flags. A classpath file is the
 * portable way to consume output from {@code mvn dependency:build-classpath}
 * or a Gradle generated runtime classpath without building a huge shell
 * argument.
 *
 * <p>Accepted file formats: one entry per line, a single line separated by
 * {@code :} or {@code ;}, or a Maven-style {@code classpath=} prefix. Blank
 * lines, {@code #} comments and {@code -classpath}/{@code -cp} markers are
 * ignored. Relative entries are resolved against the classpath file's
 * directory.</p>
 */
public final class SourceScanOptionsFactory {
    // 作用：阻止工厂类被实例化；逻辑：私有空构造器。
    private SourceScanOptionsFactory() {
    }

    // 作用：组装扫描选项；逻辑：合并命令行与文件中的 classpath 条目后构造选项对象。
    public static SourceScanOptions create(
            String languageLevel,
            boolean includeTests,
            List<String> generatedSources,
            List<String> classpathEntries,
            String classpathFile
    ) {
        List<String> merged = new ArrayList<>();
        if (classpathEntries != null) {
            merged.addAll(classpathEntries);
        }
        if (classpathFile != null && !classpathFile.isBlank()) {
            merged.addAll(readClasspathFile(Path.of(classpathFile.trim())));
        }
        return new SourceScanOptions(languageLevel, includeTests, generatedSources, merged);
    }

    // 作用：读取 classpath 文件；逻辑：多行解析、去注释与命令行前缀，按分隔符拆分并解析相对路径。
    public static List<String> readClasspathFile(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Classpath file does not exist: " + file);
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read classpath file " + file, e);
        }
        Path base = file.toAbsolutePath().getParent();
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        for (String raw : lines) {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("-classpath ") || line.startsWith("-cp ")) {
                line = line.substring(line.indexOf(' ') + 1).trim();
            }
            if (line.startsWith("classpath=")) {
                line = line.substring("classpath=".length()).trim();
            }
            for (String token : splitEntries(line)) {
                String value = token.trim();
                if (value.isEmpty()) {
                    continue;
                }
                entries.add(resolve(base, value));
            }
        }
        return List.copyOf(entries);
    }

    // 作用：解析条目路径；逻辑：绝对路径与 Windows 盘符原样返回，相对路径按文件所在目录展开。
    private static String resolve(Path base, String value) {
        boolean windowsAbsolute = value.length() > 2
                && Character.isLetter(value.charAt(0))
                && value.charAt(1) == ':';
        Path path = Path.of(value);
        if (path.isAbsolute() || windowsAbsolute || base == null) {
            return value;
        }
        return base.resolve(path).normalize().toString();
    }

    // 作用：拆分 classpath 条目；逻辑：优先按分号，其次按冒号拆分并保留 Windows 盘符。
    private static List<String> splitEntries(String line) {
        List<String> result = new ArrayList<>();
        if (line.indexOf(';') >= 0) {
            result.addAll(List.of(line.split(";")));
            return result;
        }
        if (line.indexOf(':') < 0) {
            result.add(line);
            return result;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            boolean driveColon = c == ':' && i == 1 && Character.isLetter(line.charAt(0));
            if (c == ':' && !driveColon) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());
        return result;
    }

    // 作用：去掉行内注释；逻辑：截断行首或空白之后的井号及其后内容。
    private static String stripComment(String line) {
        for (int index = 0; index < line.length(); index++) {
            if (line.charAt(index) == '#'
                    && (index == 0 || Character.isWhitespace(line.charAt(index - 1)))) {
                return line.substring(0, index);
            }
        }
        return line;
    }
}
