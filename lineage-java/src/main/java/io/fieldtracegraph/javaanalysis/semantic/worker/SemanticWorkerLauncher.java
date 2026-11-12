package io.fieldtracegraph.javaanalysis.semantic.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fieldtracegraph.core.model.Diagnostic;
import io.fieldtracegraph.javaanalysis.semantic.javac.SemanticAnalysisResult;
import io.fieldtracegraph.javaanalysis.semantic.protocol.CallAttribution;
import io.fieldtracegraph.javaanalysis.semantic.protocol.SemanticSource;
import io.fieldtracegraph.javaanalysis.semantic.protocol.SemanticWorkerRequest;
import io.fieldtracegraph.javaanalysis.semantic.protocol.SemanticWorkerResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

// 作用：启动并管理隔离的语义分析进程。
// 使用场景：主进程需要 javac 归属能力时调用。

/**
 * Owns the lifecycle of the isolated javac worker: request serialization,
 * bounded wait, forced cancellation on timeout, and private temp cleanup.
 */
public final class SemanticWorkerLauncher {
    private static final long DEFAULT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(120);
    // A large multi-module source set legitimately produces a call-attribution
    // payload well above the old 16 MiB ceiling. Keep a finite bound, but size
    // it for real repositories rather than only small samples.
    private static final long DEFAULT_MAX_RESPONSE_BYTES = 128L * 1024 * 1024;
    private static final String DEFAULT_MAX_HEAP = "512m";
    private static final int MAX_ERROR_BYTES = 4096;

    private final Path javaExecutable;
    private final String workerClasspath;
    private final long timeoutMillis;
    private final long maxResponseBytes;
    private final String maxHeap;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 作用：创建默认 worker 启动器；逻辑：使用当前 JVM 的 java、类路径、默认超时、响应上限与堆大小。
    public SemanticWorkerLauncher() {
        this(
                Path.of(System.getProperty("java.home"), "bin", "java"),
                System.getProperty("java.class.path"),
                DEFAULT_TIMEOUT_MILLIS,
                DEFAULT_MAX_RESPONSE_BYTES,
                DEFAULT_MAX_HEAP
        );
    }

    // 作用：创建可配置的 worker 启动器；逻辑：保存可执行文件、类路径、超时、响应大小上限与堆参数。
    public SemanticWorkerLauncher(
            Path javaExecutable,
            String workerClasspath,
            long timeoutMillis,
            long maxResponseBytes,
            String maxHeap
    ) {
        this.javaExecutable = javaExecutable;
        this.workerClasspath = workerClasspath;
        this.timeoutMillis = timeoutMillis;
        this.maxResponseBytes = maxResponseBytes;
        this.maxHeap = maxHeap;
    }

    // 作用：启动 worker 完成一次语义分析；逻辑：写请求、限时等待子进程、校验响应大小与状态，并清理临时目录。
    public SemanticAnalysisResult analyze(List<SemanticSource> sources, List<Path> classpath, String encoding) {
        Path workDirectory = null;
        try {
            workDirectory = Files.createTempDirectory("field-trace-graph-semantic-");
            Path requestPath = workDirectory.resolve("request.json");
            Path responsePath = workDirectory.resolve("response.json");
            Path errorPath = workDirectory.resolve("stderr.txt");

            SemanticWorkerRequest request = new SemanticWorkerRequest(
                    SemanticWorkerRequest.SCHEMA_VERSION,
                    sources,
                    classpath.stream().map(Path::toString).toList(),
                    encoding
            );
            Files.writeString(requestPath, objectMapper.writeValueAsString(request), StandardCharsets.UTF_8);

            Process process = start(requestPath, responsePath, errorPath);
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IllegalStateException("SEMANTIC_TIMEOUT");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("SEMANTIC_WORKER_FAILED: " + readLimited(errorPath, MAX_ERROR_BYTES));
            }
            long responseSize = Files.size(responsePath);
            if (responseSize > maxResponseBytes) {
                throw new IllegalStateException("SEMANTIC_OUTPUT_LIMIT");
            }
            byte[] responseBytes = Files.readAllBytes(responsePath);
            SemanticWorkerResponse response = objectMapper.readValue(responseBytes, SemanticWorkerResponse.class);
            if (!"OK".equals(response.status())) {
                throw new IllegalStateException("SEMANTIC_WORKER_FAILED");
            }
            List<Diagnostic> diagnostics = response.diagnostics().stream()
                    .map(diagnostic -> new Diagnostic(
                            diagnostic.code(),
                            diagnostic.message(),
                            diagnostic.relativePath(),
                            diagnostic.line(),
                            false,
                            false
                    ))
                    .toList();
            List<CallAttribution> attributions = response.attributions() == null
                    ? List.of()
                    : List.copyOf(response.attributions());
            return new SemanticAnalysisResult(diagnostics, attributions);
        } catch (IOException e) {
            throw new IllegalStateException("SEMANTIC_WORKER_IO: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SEMANTIC_WORKER_INTERRUPTED", e);
        } finally {
            deleteRecursively(workDirectory);
        }
    }

    // 作用：启动语义 worker 子进程；逻辑：拼装 java 命令与堆参数，把标准错误重定向到独立文件。
    private Process start(Path requestPath, Path responsePath, Path errorPath) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Xmx" + maxHeap);
        command.add("-cp");
        command.add(workerClasspath);
        command.add(SemanticWorkerMain.class.getName());
        command.add(requestPath.toString());
        command.add(responsePath.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(errorPath.toFile());
        return builder.start();
    }

    // 作用：限量读取错误输出；逻辑：文件不存在或读取失败时返回空串，否则最多读取指定字节。
    private String readLimited(Path path, int limit) {
        if (!Files.exists(path)) {
            return "";
        }
        try (InputStream input = Files.newInputStream(path)) {
            return new String(input.readNBytes(limit), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // 作用：递归删除临时工作目录；逻辑：空路径直接返回，否则逆序尽力删除所有文件与目录。
    private void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
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
