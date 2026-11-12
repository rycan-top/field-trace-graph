package io.fieldtracegraph.cli.project;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// 作用：从当前目录向上查找项目根。
// 使用场景：未显式传入 --project 时确定分析目标。

public class ProjectRootResolver {
    // 作用：解析项目根目录；逻辑：显式路径直接规范化，否则从当前目录向上探测并校验目录存在。
    public Path resolve(String explicitProject) {
        try {
            Path root = explicitProject == null || explicitProject.isBlank()
                    ? detectRoot(Paths.get(System.getProperty("user.dir")))
                    : Paths.get(explicitProject).toRealPath();
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("Project path is not a directory: " + root);
            }
            return root;
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to resolve project path", e);
        }
    }

    // 作用：向上探测项目根；逻辑：优先识别 .git，其次识别唯一构建文件，多个构建文件时报歧义。
    private Path detectRoot(Path start) throws IOException {
        Path current = start.toRealPath();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            if (hasBuildFile(current)) {
                if (countBuildFiles(current) > 1) {
                    throw new IllegalArgumentException("AMBIGUOUS_PROJECT_ROOT: multiple build descriptors at " + current);
                }
                return current;
            }
            current = current.getParent();
        }
        return start.toRealPath();
    }

    // 作用：判断目录是否含构建文件；逻辑：检查 Maven 与 Gradle 的三种描述文件。
    private boolean hasBuildFile(Path directory) {
        return Files.exists(directory.resolve("pom.xml"))
                || Files.exists(directory.resolve("build.gradle"))
                || Files.exists(directory.resolve("build.gradle.kts"));
    }

    // 作用：统计构建文件数量；逻辑：逐项检查并累加存在的描述文件。
    private int countBuildFiles(Path directory) {
        int count = 0;
        if (Files.exists(directory.resolve("pom.xml"))) {
            count++;
        }
        if (Files.exists(directory.resolve("build.gradle"))) {
            count++;
        }
        if (Files.exists(directory.resolve("build.gradle.kts"))) {
            count++;
        }
        return count;
    }
}
