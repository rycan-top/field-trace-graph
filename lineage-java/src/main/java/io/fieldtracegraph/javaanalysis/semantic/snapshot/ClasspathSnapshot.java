package io.fieldtracegraph.javaanalysis.semantic.snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

// 作用：表示一份固定的 classpath 快照。
// 使用场景：保证一次语义分析期间依赖内容不发生变化。

/**
 * A controlled temporary copy of validated classpath entries. Closing the
 * snapshot removes the private directory; the original entries are never
 * modified.
 */
public final class ClasspathSnapshot implements AutoCloseable {
    private final Path directory;
    private final List<Path> paths;

    // 作用：保存快照目录与条目路径；逻辑：直接持有临时目录和复制后的 classpath 列表。
    ClasspathSnapshot(Path directory, List<Path> paths) {
        this.directory = directory;
        this.paths = paths;
    }

    // 作用：返回快照中的 classpath 路径；逻辑：直接返回构建时保存的不可变列表。
    public List<Path> paths() {
        return paths;
    }

    @Override
    // 作用：删除临时快照；逻辑：按逆序遍历目录并尽力删除，清理失败时保留给系统临时目录回收。
    public void close() {
        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup; the OS temp directory is the fallback.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
