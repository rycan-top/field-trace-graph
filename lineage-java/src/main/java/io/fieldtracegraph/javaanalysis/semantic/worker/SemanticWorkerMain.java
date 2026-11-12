package io.fieldtracegraph.javaanalysis.semantic.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fieldtracegraph.javaanalysis.semantic.javac.JavacSemanticAnalyzer;
import io.fieldtracegraph.javaanalysis.semantic.javac.SemanticAnalysisResult;
import io.fieldtracegraph.javaanalysis.semantic.protocol.SemanticWorkerDiagnostic;
import io.fieldtracegraph.javaanalysis.semantic.protocol.SemanticWorkerRequest;
import io.fieldtracegraph.javaanalysis.semantic.protocol.SemanticWorkerResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// 作用：语义分析 worker 的进程入口。
// 使用场景：在独立 JVM 中执行 javac，隔离依赖与内存影响。

/**
 * Private entry point for the isolated javac worker. It is not a user-facing
 * command; the launcher controls the request/response files and process
 * lifetime.
 */
public final class SemanticWorkerMain {
    // 作用：运行隔离的语义分析 worker；逻辑：读取请求、校验协议并执行 javac 分析，成功或失败都写回响应文件。
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: SemanticWorkerMain <request-file> <response-file>");
            System.exit(2);
            return;
        }
        Path requestPath = Path.of(args[0]);
        Path responsePath = Path.of(args[1]);
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            SemanticWorkerRequest request = objectMapper.readValue(Files.readAllBytes(requestPath), SemanticWorkerRequest.class);
            if (request.schemaVersion() != SemanticWorkerRequest.SCHEMA_VERSION) {
                throw new IllegalArgumentException("UNSUPPORTED_WORKER_PROTOCOL: " + request.schemaVersion());
            }
            SemanticAnalysisResult result = new JavacSemanticAnalyzer().analyze(
                    request.sources(),
                    request.classpath().stream().map(Path::of).toList(),
                    request.encoding()
            );
            List<SemanticWorkerDiagnostic> workerDiagnostics = result.diagnostics().stream()
                    .map(diagnostic -> new SemanticWorkerDiagnostic(
                            diagnostic.code(),
                            diagnostic.message(),
                            diagnostic.relativePath(),
                            diagnostic.line()
                    ))
                    .toList();
            SemanticWorkerResponse response = new SemanticWorkerResponse(
                    SemanticWorkerResponse.SCHEMA_VERSION,
                    "OK",
                    workerDiagnostics,
                    result.attributions()
            );
            Files.writeString(responsePath, objectMapper.writeValueAsString(response), StandardCharsets.UTF_8);
        } catch (Throwable throwable) {
            writeFailure(objectMapper, responsePath, throwable);
            System.err.println(throwable.getMessage());
            System.exit(1);
        }
    }

    // 作用：写入失败响应；逻辑：构造带错误诊断的失败结果并尽力落盘，落盘失败时仍依赖非零退出码。
    private static void writeFailure(ObjectMapper objectMapper, Path responsePath, Throwable throwable) {
        try {
            SemanticWorkerResponse response = new SemanticWorkerResponse(
                    SemanticWorkerResponse.SCHEMA_VERSION,
                    "FAILED",
                    List.of(new SemanticWorkerDiagnostic("SEMANTIC_WORKER_FAILED", String.valueOf(throwable.getMessage()), null, null)),
                    List.of()
            );
            Files.writeString(responsePath, objectMapper.writeValueAsString(response), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // The non-zero exit code remains the authoritative failure signal.
        }
    }
}
