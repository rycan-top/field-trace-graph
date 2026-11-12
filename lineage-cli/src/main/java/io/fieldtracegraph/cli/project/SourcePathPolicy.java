package io.fieldtracegraph.cli.project;

import io.fieldtracegraph.cli.config.LineageRuntimeProperties;

import java.nio.file.Files;
import java.nio.file.Path;

// 作用：定义源码路径的包含与排除规则。
// 使用场景：过滤测试目录、构建目录与生成目录。

public final class SourcePathPolicy {
    private final int maxFiles;
    private final long maxFileBytes;
    private final long maxTotalBytes;
    private final int maxDepth;

    // 作用：创建自定义预算策略；逻辑：保存四项扫描上限。
    public SourcePathPolicy(int maxFiles, long maxFileBytes, long maxTotalBytes, int maxDepth) {
        this.maxFiles = maxFiles;
        this.maxFileBytes = maxFileBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.maxDepth = maxDepth;
    }

    // 作用：由运行配置创建策略；逻辑：读取配置中的扫描预算并构造实例。
    public static SourcePathPolicy from(LineageRuntimeProperties options) {
        return new SourcePathPolicy(
                options.maxSourceFiles(),
                options.maxSourceFileBytes(),
                options.maxTotalSourceBytes(),
                options.maxSourceDepth()
        );
    }

    // 作用：读取最大目录深度；逻辑：返回构造时设置的深度上限。
    public int maxDepth() {
        return maxDepth;
    }

    // 作用：校验并规范化相对路径；逻辑：拒绝符号链接、越界路径与包含上级跳转的路径。
    public String validateRelativePath(Path root, Path file) {
        if (Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException("Symbolic link is not allowed: " + file);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Path escapes the project root: " + file);
        }
        String relative = normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
        if (relative.isBlank() || relative.startsWith("..") || relative.contains("/../")) {
            throw new IllegalArgumentException("Unsafe relative path: " + file);
        }
        return relative;
    }

    // 作用：校验单文件大小；逻辑：超过上限时抛出参数异常。
    public void enforceFileSize(long bytes) {
        if (bytes > maxFileBytes) {
            throw new IllegalArgumentException("Source file exceeds the byte limit: " + bytes);
        }
    }

    // 作用：校验扫描总量；逻辑：文件数或总字节超限时抛出参数异常。
    public void enforceTotals(int fileCount, long totalBytes) {
        if (fileCount > maxFiles) {
            throw new IllegalArgumentException("Source file count exceeds the limit: " + maxFiles);
        }
        if (totalBytes > maxTotalBytes) {
            throw new IllegalArgumentException("Total source bytes exceed the limit: " + maxTotalBytes);
        }
    }
}
