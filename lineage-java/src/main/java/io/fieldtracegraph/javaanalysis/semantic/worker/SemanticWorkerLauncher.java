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
import java.lang.management.ManagementFactory;
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
    private static final long TIMEOUT_PER_SOURCE_MILLIS = 50;
    private static final long MAX_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);
    // A large multi-module source set legitimately produces a call-attribution
    // payload well above the old 16 MiB ceiling. Keep a finite bound, but size
    // it for real repositories rather than only small samples.
    private static final long DEFAULT_MAX_RESPONSE_BYTES = 128L * 1024 * 1024;
    private static final String DEFAULT_MAX_HEAP = "512m";
    private static final String MAX_HEAP_PROPERTY = "field-trace-graph.semantic.worker.heap";
    private static final String MAX_HEAP_ENV = "FTG_SEMANTIC_WORKER_HEAP";
    private static final long MIN_HEAP_BYTES = 1024L * 1024 * 1024;
    private static final long MAX_HEAP_BYTES = 8L * 1024 * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 4096;

    private final Path javaExecutable;
    private final String workerClasspath;
    private final long timeoutMillis;
    private final long maxResponseBytes;
    private String maxHeap;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 作用：创建默认 worker 启动器；逻辑：使用当前 JVM 的 java、类路径、默认超时、响应上限与堆大小。
    public SemanticWorkerLauncher() {
        this(
                Path.of(System.getProperty("java.home"), "bin", "java"),
                System.getProperty("java.class.path"),
                DEFAULT_TIMEOUT_MILLIS,
                DEFAULT_MAX_RESPONSE_BYTES,
                defaultMaxHeap()
        );
    }

    // 作用：计算语义 worker 的默认堆上限；逻辑：显式配置优先，否则按主进程最大堆的 75% 估算并夹在 1 GiB 与 8 GiB 之间。
    static String defaultMaxHeap() {
        String configured = System.getProperty(MAX_HEAP_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(MAX_HEAP_ENV);
        }
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        long runtimeMax = Runtime.getRuntime().maxMemory();
        if (runtimeMax <= 0 || runtimeMax == Long.MAX_VALUE) {
            return "2g";
        }
        long target = Math.max(MIN_HEAP_BYTES, Math.round(runtimeMax * 0.75));
        target = Math.min(target, heapCeilingBytes());
        return (target / (1024 * 1024)) + "m";
    }

    // 作用：给出 worker 堆的物理上限；逻辑：取物理内存的 60%，并夹在 1 GiB 与 8 GiB 之间，避免把机器压垮。
    private static long heapCeilingBytes() {
        long totalPhysical = 0;
        try {
            var bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean osBean) {
                totalPhysical = osBean.getTotalMemorySize();
            }
        } catch (RuntimeException ignored) {
            // Fall back to the fixed ceiling when the platform bean is unavailable.
        }
        if (totalPhysical <= 0) {
            return MAX_HEAP_BYTES;
        }
        long target = Math.round(totalPhysical * 0.6);
        return Math.max(MIN_HEAP_BYTES, Math.min(target, MAX_HEAP_BYTES));
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

    // 作用：启动 worker 完成语义分析；逻辑：堆不足导致子进程 OOM 时自动倍增重试，其余失败原样抛出。
    public SemanticAnalysisResult analyze(List<SemanticSource> sources, List<Path> classpath, String encoding) {
        long heapBytes = parseHeapBytes(maxHeap, 2L * 1024 * 1024 * 1024);
        long ceiling = heapCeilingBytes();
        long timeout = Math.min(MAX_TIMEOUT_MILLIS,
                Math.max(timeoutMillis, DEFAULT_TIMEOUT_MILLIS + sources.size() * TIMEOUT_PER_SOURCE_MILLIS));
        for (int attempt = 0; ; attempt++) {
            try {
                return analyzeOnce(sources, classpath, encoding, formatHeap(heapBytes), timeout);
            } catch (IllegalStateException failure) {
                boolean heapFailure = isHeapFailure(failure.getMessage());
                long next = Math.min(ceiling, heapBytes * 2);
                if (!heapFailure || next <= heapBytes || attempt >= 3) {
                    throw failure;
                }
                System.err.println("[warn] SEMANTIC_HEAP_RETRY: " + formatHeap(heapBytes)
                        + " -> " + formatHeap(next) + " after " + firstLine(failure.getMessage()));
                heapBytes = next;
                maxHeap = formatHeap(heapBytes);
            }
        }
    }

    // 作用：启动 worker 完成一次语义分析；逻辑：写请求、限时等待子进程、校验响应大小与状态，并清理临时目录。
    private SemanticAnalysisResult analyzeOnce(
            List<SemanticSource> sources,
            List<Path> classpath,
            String encoding,
            String heap,
            long timeout
    ) {
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

            Process process = start(requestPath, responsePath, errorPath, heap);
            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IllegalStateException("SEMANTIC_TIMEOUT after " + timeout + "ms (heap="
                        + heap + ", sources=" + sources.size() + ")");
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

    // 作用：判断失败是否由 worker 堆不足引起；逻辑：匹配 OOM 关键字，其它语义或 IO 失败不触发扩容重试。
    private static boolean isHeapFailure(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("java heap space")
                || lower.contains("outofmemoryerror")
                || lower.contains("gc overhead limit exceeded");
    }

    // 作用：解析 -Xmx 风格堆参数；逻辑：支持 k/m/g 后缀与纯数字，非法输入回退到给定默认值。
    static long parseHeapBytes(String text, long fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String value = text.trim().toLowerCase();
        long multiplier = 1;
        char unit = value.charAt(value.length() - 1);
        if (unit == 'k' || unit == 'm' || unit == 'g' || unit == 't') {
            value = value.substring(0, value.length() - 1);
            multiplier = switch (unit) {
                case 'k' -> 1024L;
                case 'm' -> 1024L * 1024;
                case 'g' -> 1024L * 1024 * 1024;
                default -> 1024L * 1024 * 1024 * 1024;
            };
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed * multiplier : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    // 作用：把字节数格式化为 -Xmx 参数；逻辑：按 MiB 取整，避免出现小数。
    private static String formatHeap(long bytes) {
        long mib = Math.max(1, bytes / (1024 * 1024));
        return mib + "m";
    }

    // 作用：截取错误信息首行；逻辑：用于单行告警，避免把整段 stderr 打散到日志里。
    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    // 作用：启动语义 worker 子进程；逻辑：拼装 java 命令与堆参数，把标准错误重定向到独立文件。
    private Process start(Path requestPath, Path responsePath, Path errorPath, String heap) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Xmx" + heap);
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
