package io.fieldtracegraph.cli.application;

import io.fieldtracegraph.cli.project.ProjectRootResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

// 作用：解析项目依赖类路径。
// 使用场景：索引前补齐外部 JAR，减少 CLASSPATH_MISSING 弱跳。

/**
 * Builds a classpath file from the project's Maven modules.
 *
 * <p>Most {@code CLASSPATH_MISSING} weak hops are external types the analyser
 * cannot see. Maven already knows the answer, so this service runs
 * {@code dependency:build-classpath} and unions the results into one entry per
 * line in the format {@code --classpath-file} accepts.</p>
 *
 * <p>A reactor build normally aborts at the first module whose dependency
 * cannot be resolved. That is common for private repositories that are not
 * configured locally, and it used to turn the whole command into
 * {@code entries=0}. The reactor is therefore executed once with
 * {@code --fail-at-end} and a module-relative output file: every resolvable
 * module still writes its own classpath, and only unreachable modules are
 * reported as failures.</p>
 */
public class ClasspathResolutionService {
    private static final int MAX_MODULES_DEFAULT = 12;
    private static final int MODULE_TIMEOUT_DEFAULT = 180;
    private static final int MAX_FAILURE_LINES = 3;
    private static final int MAX_FAILURE_LENGTH = 240;

    private final ProjectRootResolver projectRootResolver;

    // 作用：注入项目解析器；逻辑：保存根目录解析依赖。
    public ClasspathResolutionService(ProjectRootResolver projectRootResolver) {
        this.projectRootResolver = projectRootResolver;
    }

    // 作用：解析类路径；逻辑：先用 reactor 批量解析，再对未产出的模块单独回退，最后合并去重写出文件。
    public Summary resolve(
            String explicitProject,
            Path output,
            int maxModules,
            int moduleTimeoutSeconds,
            boolean offlineOnly
    ) throws IOException, InterruptedException {
        Path root = projectRootResolver.resolve(explicitProject);
        List<Path> poms = discoverPoms(root);
        if (poms.isEmpty()) {
            throw new IllegalStateException(
                    "No pom.xml found under " + root + "; provide --classpath-file manually");
        }
        int limit = maxModules <= 0 ? MAX_MODULES_DEFAULT : maxModules;
        int timeout = moduleTimeoutSeconds <= 0 ? MODULE_TIMEOUT_DEFAULT : moduleTimeoutSeconds;
        String token = "ftg-classpath-" + Long.toHexString(System.nanoTime());
        Map<Path, String> failures = new LinkedHashMap<>();
        Map<Path, Path> produced = new LinkedHashMap<>();

        Path rootPom = poms.get(0);
        MavenRun reactor = runReactor(rootPom, token, timeout, true);
        if (!reactor.success() && !offlineOnly) {
            reactor = runReactor(rootPom, token, timeout, false);
        }
        produced.putAll(collectOutputs(root, token));
        int modulesRun = 1;
        for (Path pom : poms) {
            if (modulesRun >= limit) {
                break;
            }
            Path moduleDir = pom.getParent();
            if (produced.containsKey(moduleDir)) {
                continue;
            }
            modulesRun++;
            Path out = moduleDir.resolve("target").resolve(token + ".txt");
            Files.createDirectories(out.getParent());
            MavenRun run = runMaven(pom, out, timeout, true);
            if (!run.success() && !offlineOnly) {
                run = runMaven(pom, out, timeout, false);
            }
            if (run.success() && Files.isRegularFile(out) && Files.size(out) > 0) {
                produced.put(moduleDir, out);
            } else {
                failures.put(pom, run.success() ? "no classpath entries resolved" : run.failure());
            }
        }
        if (produced.isEmpty() && failures.isEmpty()) {
            failures.put(rootPom, reactor.failure());
        }

        Set<String> entries = new LinkedHashSet<>();
        List<Path> ordered = new ArrayList<>(produced.keySet());
        ordered.sort(Comparator.comparing(Path::toString));
        for (Path moduleDir : ordered) {
            Path file = produced.get(moduleDir);
            entries.addAll(readEntries(file));
            Files.deleteIfExists(file);
        }
        int mavenEntries = entries.size();
        if (!failures.isEmpty() || entries.isEmpty()) {
            /*
             * Unresolvable private artifacts make Maven skip the whole module,
             * even when its public dependencies are already in the local
             * repository. Recovering those jars keeps external accessors
             * provable without inventing coordinates.
             */
            try {
                entries.addAll(new LocalRepositoryClasspath().entries(root, poms, 400));
            } catch (RuntimeException ignored) {
                // Recovery is opportunistic; Maven results remain authoritative.
            }
        }

        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(output, entries, StandardCharsets.UTF_8);
        List<String> failureMessages = new ArrayList<>();
        failures.forEach((pom, reason) -> failureMessages.add(
                root.relativize(pom) + ": " + reason));
        return new Summary(
                root,
                output,
                Math.min(modulesRun, limit),
                entries.size(),
                entries.size() - mavenEntries,
                List.copyOf(failureMessages)
        );
    }

    // 作用：以 reactor 方式解析全部模块；逻辑：使用 --fail-at-end 与模块相对输出路径，任一模块成功即视为有产出。
    private MavenRun runReactor(Path rootPom, String token, int timeoutSeconds, boolean offline)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                "mvn", "-q", "-B", "-fae", "-f", rootPom.toString(),
                "dependency:build-classpath",
                "-Dmdep.outputFile=target/" + token + ".txt",
                "-DincludeScope=runtime"
        ));
        if (offline) {
            command.add(1, "-o");
        }
        Path log = Files.createTempFile("ftg-classpath-", ".log");
        try {
            int exit = runCommand(command, log, rootPom.getParent(), timeoutSeconds);
            boolean produced = !collectOutputs(rootPom.getParent(), token).isEmpty();
            return produced
                    ? new MavenRun(true, "")
                    : new MavenRun(false, summarize(log, exit));
        } finally {
            Files.deleteIfExists(log);
        }
    }

    // 作用：执行一次 Maven 类路径解析；逻辑：按模块输出到临时文件并检查退出码。
    private MavenRun runMaven(Path pom, Path out, int timeoutSeconds, boolean offline)
            throws IOException, InterruptedException {
        Path moduleDir = pom.getParent();
        List<String> command = new ArrayList<>(List.of(
                "mvn", "-q", "-B", "-f", pom.toString(),
                "dependency:build-classpath",
                "-Dmdep.outputFile=" + moduleDir.relativize(out),
                "-DincludeScope=runtime"
        ));
        if (offline) {
            command.add(1, "-o");
        }
        Path log = Files.createTempFile("ftg-classpath-", ".log");
        try {
            int exit = runCommand(command, log, moduleDir, timeoutSeconds);
            boolean success = exit == 0 && Files.isRegularFile(out) && Files.size(out) > 0;
            return success ? new MavenRun(true, "") : new MavenRun(false, summarize(log, exit));
        } finally {
            Files.deleteIfExists(log);
        }
    }

    // 作用：执行外部命令；逻辑：输出重定向到日志文件，超时后连同子进程一并强制结束。
    private int runCommand(List<String> command, Path log, Path workingDirectory, int timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            return -1;
        }
        return process.exitValue();
    }

    // 作用：收集模块输出文件；逻辑：按本次运行标记精确匹配，避免读到历史产物。
    private Map<Path, Path> collectOutputs(Path root, String token) throws IOException {
        Map<Path, Path> outputs = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(root, 5)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> (token + ".txt").equals(path.getFileName().toString()))
                    .filter(path -> path.getParent() != null
                            && "target".equals(path.getParent().getFileName().toString()))
                    .filter(path -> {
                        try {
                            return Files.size(path) > 0;
                        } catch (IOException ignored) {
                            return false;
                        }
                    })
                    .forEach(path -> outputs.putIfAbsent(path.getParent().getParent(), path));
        }
        return outputs;
    }

    // 作用：读取 classpath 文件；逻辑：跳过日志行与空项，按路径分隔符拆分。
    private List<String> readEntries(Path file) throws IOException {
        List<String> entries = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String value = line.strip();
            if (value.isEmpty() || value.startsWith("[")) {
                continue;
            }
            for (String entry : value.split(java.io.File.pathSeparator)) {
                if (!entry.isBlank()) {
                    entries.add(entry.strip());
                }
            }
        }
        return entries;
    }

    // 作用：提取 Maven 失败原因；逻辑：取日志末尾非空行，超时单独说明，最后截断展示长度。
    private String summarize(Path log, int exit) {
        if (exit < 0) {
            return "maven timed out";
        }
        String last = "";
        String preferred = "";
        try {
            List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
            for (int index = all.size() - 1; index >= 0; index--) {
                String line = all.get(index).strip();
                if (line.isEmpty()) {
                    continue;
                }
                if (last.isEmpty()) {
                    last = line;
                }
                if (preferred.isEmpty() && line.contains("[ERROR]") && isPrimaryFailure(line)) {
                    preferred = line;
                }
                if (!preferred.isEmpty() && !last.isEmpty()) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // Reporting the failure must never fail the command.
        }
        if (!preferred.isEmpty()) {
            last = preferred;
        }
        if (last.isEmpty()) {
            return exit == 127 || exit < 0
                    ? "mvn is not available on PATH"
                    : "maven failed with exit " + exit + " (no diagnostics captured)";
        }
        return last.length() <= MAX_FAILURE_LENGTH
                ? last
                : last.substring(0, MAX_FAILURE_LENGTH) + "...";
    }

    // 作用：识别主要失败行；逻辑：优先依赖解析类错误，而不是 Maven 的帮助链接。
    private boolean isPrimaryFailure(String line) {
        return line.contains("Could not resolve")
                || line.contains("Could not collect")
                || line.contains("Failed to read artifact")
                || line.contains("Failure to find")
                || line.contains("Cannot access")
                || line.contains("Non-resolvable")
                || line.contains("has not been downloaded")
                || line.contains("in offline mode");
    }

    // 作用：发现模块 POM；逻辑：跳过构建目录，优先根 POM，其余按路径排序。
    private List<Path> discoverPoms(Path root) throws IOException {
        List<Path> poms = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 4)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> "pom.xml".equals(path.getFileName().toString()))
                    .filter(path -> !path.toString().contains("/target/"))
                    .filter(path -> !path.toString().contains("/.git/"))
                    .filter(path -> !path.toString().contains("/.field-trace-graph/"))
                    .forEach(poms::add);
        }
        poms.sort(Comparator.comparingInt((Path path) -> root.equals(path.getParent()) ? 0 : 1)
                .thenComparing(Path::toString));
        return poms;
    }

    private record MavenRun(boolean success, String failure) {
    }

    public record Summary(
            Path root,
            Path output,
            int modules,
            int entries,
            int localRepositoryEntries,
            List<String> failures
    ) {
    }
}
