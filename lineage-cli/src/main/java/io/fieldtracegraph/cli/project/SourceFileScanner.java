package io.fieldtracegraph.cli.project;

import io.fieldtracegraph.cli.contract.ProjectContractLoader;
import io.fieldtracegraph.cli.contract.ProjectContracts;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.SourceScanOptions;
import io.fieldtracegraph.core.shared.hash.Sha256Digest;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.project.JavaLanguageLevelResolver;
import io.fieldtracegraph.javaanalysis.project.LanguageLevelResolution;
import io.fieldtracegraph.cli.log.CliLog;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

// 作用：遍历源码目录并收集候选文件。
// 使用场景：建立索引时枚举全部待分析源码。

public final class SourceFileScanner {
    private static final List<String> EXCLUDED_DIRECTORIES = List.of(
            ".git", ".field-trace-graph", ".idea", ".vscode", "target", "build", "out", "dist", "node_modules"
    );

    private final SourcePathPolicy pathPolicy;
    private final JavaLanguageLevelResolver languageLevelResolver;
    private final ProjectContractLoader contractLoader = new ProjectContractLoader();
    private final Set<String> assumedModulesWarned = new HashSet<>();

    // 作用：创建自定义扫描器；逻辑：保存路径策略与语言级别解析器供扫描复用。
    public SourceFileScanner(SourcePathPolicy pathPolicy, JavaLanguageLevelResolver languageLevelResolver) {
        this.pathPolicy = pathPolicy;
        this.languageLevelResolver = languageLevelResolver;
    }

    // 作用：扫描项目源码；逻辑：合并生成源与常规源，按排序逐文件读取、计量并校验预算。
    public List<SourceFileRecord> scan(Path projectRoot, SourceScanOptions options) {
        List<SourceFileRecord> result = new ArrayList<>();
        GitIgnoreRules ignoreRules = GitIgnoreRules.load(projectRoot);
        Set<Path> filesToRead = new LinkedHashSet<>();
        for (Path generatedRoot : generatedRoots(projectRoot, options)) {
            filesToRead.addAll(explicitJavaFiles(projectRoot, generatedRoot));
        }
        long totalBytes = 0;
        try {
            try (Stream<Path> stream = Files.walk(projectRoot, pathPolicy.maxDepth())) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .filter(path -> !isExcluded(projectRoot, path, ignoreRules))
                        .filter(path -> options.includeTests() || !isTestSource(projectRoot, path))
                        .forEach(filesToRead::add);
            }
            List<Path> files = filesToRead.stream()
                    .sorted(Comparator.comparing(path -> projectRoot.relativize(path).toString()))
                    .toList();
            for (Path file : files) {
                SourceFileRecord record;
                try {
                    record = read(projectRoot, file, options.languageLevelOverride());
                } catch (CharacterCodingException e) {
                    record = undecodable(projectRoot, file, options.languageLevelOverride(), e);
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Unable to read source file " + projectRoot.relativize(file), e);
                }
                totalBytes += record.fileSize();
                result.add(record);
                pathPolicy.enforceTotals(result.size(), totalBytes);
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to scan project sources", e);
        }
    }

    // 作用：解析生成源根目录；逻辑：合并选项与契约配置，校验目录存在且不越出项目根。
    private List<Path> generatedRoots(Path projectRoot, SourceScanOptions options) {
        List<Path> result = new ArrayList<>();
        List<String> configuredRoots = new ArrayList<>(options.generatedSourceRoots());
        ProjectContracts contracts = contractLoader.load(projectRoot);
        configuredRoots.addAll(contracts.generatedSources());
        for (String configured : configuredRoots.stream().distinct().toList()) {
            Path root = projectRoot.resolve(configured).toAbsolutePath().normalize();
            if (!root.startsWith(projectRoot.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException(
                        "Generated source root escapes the project: " + configured
                );
            }
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException(
                        "Generated source root does not exist: " + configured
                );
            }
            pathPolicy.validateRelativePath(projectRoot, root);
            result.add(root);
        }
        return result;
    }

    // 作用：枚举生成源下的 Java 文件；逻辑：按深度遍历并过滤硬排除目录。
    private List<Path> explicitJavaFiles(Path projectRoot, Path generatedRoot) {
        try (Stream<Path> stream = Files.walk(generatedRoot, pathPolicy.maxDepth())) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !hardExcluded(projectRoot, path))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to scan generated source root " + projectRoot.relativize(generatedRoot),
                    e
            );
        }
    }

    // 作用：判断路径是否被硬排除；逻辑：逐段检查版本控制、IDE 与依赖目录名。
    private boolean hardExcluded(Path root, Path file) {
        Path relative = root.relativize(file);
        for (int i = 0; i < relative.getNameCount(); i++) {
            String segment = relative.getName(i).toString();
            if (".git".equals(segment) || ".idea".equals(segment)
                    || ".vscode".equals(segment) || "node_modules".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    // 作用：读取单个源文件；逻辑：校验路径与大小，计算哈希、解码内容并解析所属模块与语言级别。
    private SourceFileRecord read(Path root, Path file, String languageLevelOverride) throws IOException {
        String relativePath = pathPolicy.validateRelativePath(root, file);
        byte[] bytes = Files.readAllBytes(file);
        pathPolicy.enforceFileSize(bytes.length);
        String hash = Sha256Digest.of(bytes);
        SourceFileReader.DecodedSource decoded = SourceFileReader.decode(bytes);
        Path moduleDirectory = moduleDirectory(root, file);
        String moduleId = moduleId(root, moduleDirectory);
        LanguageLevelResolution languageLevel = languageLevelResolver.resolve(root, moduleDirectory, languageLevelOverride);
        if (languageLevel.assumed() && assumedModulesWarned.add(moduleId)) {
            CliLog.warn("assumed Java language level " + languageLevel.level()
                    + " for module " + moduleId + " (" + languageLevel.reason() + ")");
        }
        return new SourceFileRecord(
                Ids.sha256(relativePath),
                relativePath,
                moduleId,
                hash,
                bytes.length,
                decoded.encoding(),
                languageLevel.level(),
                "READY",
                null,
                decoded.content(),
                file
        );
    }

    // 作用：构造无法解码的文件记录；逻辑：保留路径、大小与模块信息，标记 PARTIAL 与解码失败。
    private SourceFileRecord undecodable(
            Path root,
            Path file,
            String languageLevelOverride,
            CharacterCodingException failure
    ) throws IOException {
        String relativePath = pathPolicy.validateRelativePath(root, file);
        long size = Files.size(file);
        Path moduleDirectory = moduleDirectory(root, file);
        String moduleId = moduleId(root, moduleDirectory);
        LanguageLevelResolution languageLevel =
                languageLevelResolver.resolve(root, moduleDirectory, languageLevelOverride);
        return new SourceFileRecord(
                Ids.sha256(relativePath),
                relativePath,
                moduleId,
                Ids.sha256(relativePath, "DECODE_FAILED"),
                size,
                "UNKNOWN",
                languageLevel.level(),
                "PARTIAL",
                "DECODE_FAILED",
                "",
                file
        );
    }

    // 作用：定位文件所属模块目录；逻辑：从父目录向上查找最近的构建描述文件。
    private Path moduleDirectory(Path root, Path file) {
        Path parent = file.getParent();
        while (parent != null && parent.startsWith(root)) {
            if (Files.exists(parent.resolve("pom.xml"))
                    || Files.exists(parent.resolve("build.gradle"))
                    || Files.exists(parent.resolve("build.gradle.kts"))) {
                return parent;
            }
            parent = parent.getParent();
        }
        return root;
    }

    // 作用：计算模块标识；逻辑：取模块相对项目根的路径，根目录用点表示。
    private String moduleId(Path root, Path moduleDirectory) {
        String moduleId = root.relativize(moduleDirectory).toString().replace('\\', '/');
        return moduleId.isEmpty() ? "." : moduleId;
    }

    // 作用：判断文件是否排除；逻辑：先查硬编码目录名，再匹配 .gitignore 规则。
    private boolean isExcluded(Path root, Path file, GitIgnoreRules ignoreRules) {
        Path relative = root.relativize(file);
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (EXCLUDED_DIRECTORIES.contains(relative.getName(i).toString())) {
                return true;
            }
        }
        return ignoreRules.isIgnored(relative.toString());
    }

    // 作用：判断是否测试源码；逻辑：检查路径是否位于常见测试源目录下。
    private boolean isTestSource(Path root, Path file) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        return relative.contains("/src/test/")
                || relative.contains("/src/testFixtures/")
                || relative.contains("/src/integrationTest/")
                || relative.contains("/src/androidTest/");
    }

}
