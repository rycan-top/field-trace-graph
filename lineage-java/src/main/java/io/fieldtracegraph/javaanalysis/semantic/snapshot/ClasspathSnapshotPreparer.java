package io.fieldtracegraph.javaanalysis.semantic.snapshot;

import io.fieldtracegraph.javaanalysis.project.ValidatedClasspath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;

// 作用：准备 classpath 快照。
// 使用场景：启动语义 worker 前固定依赖集合与顺序。

/**
 * Copies approved classpath entries into a private temporary snapshot so the
 * bytes handed to javac are the same bytes that were validated.
 */
public final class ClasspathSnapshotPreparer {
    // 作用：创建 classpath 私有快照；逻辑：新建临时目录，按顺序复制每个文件或目录，返回可关闭的快照。
    public ClasspathSnapshot prepare(ValidatedClasspath validated) {
        try {
            Path directory = Files.createTempDirectory("field-trace-graph-classpath-");
            List<Path> copies = new ArrayList<>();
            int ordinal = 0;
            for (Path entry : validated.paths()) {
                Path copy = directory.resolve((ordinal++) + "-" + entry.getFileName());
                if (Files.isDirectory(entry)) {
                    copyDirectory(entry, copy);
                } else {
                    Files.copy(entry, copy);
                }
                copies.add(copy);
            }
            return new ClasspathSnapshot(directory, List.copyOf(copies));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to prepare classpath snapshot", e);
        }
    }

    // 作用：递归复制 classpath 目录；逻辑：按自然顺序遍历并保持相对结构，目录建目录、文件复制文件。
    private void copyDirectory(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            for (Path path : walk.sorted(Comparator.naturalOrder()).toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }
}
