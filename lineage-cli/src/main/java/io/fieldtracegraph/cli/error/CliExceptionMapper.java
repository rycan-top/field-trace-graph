package io.fieldtracegraph.cli.error;

// 作用：把异常映射为稳定的退出码。
// 使用场景：所有命令的统一错误出口。

/**
 * Translates thrown application failures into the stable exit-code contract.
 * Message codes are deliberately machine-readable so controllers do not guess
 * from exception class names alone.
 */
public final class CliExceptionMapper {
    // 作用：把异常映射为退出码；逻辑：参数错误归用法错误，索引过期或资源限制按消息码分类。
    public int exitCode(Throwable cause) {
        if (cause instanceof IllegalArgumentException) {
            return ExitCode.USAGE;
        }
        String message = cause == null ? "" : String.valueOf(cause.getMessage());
        if (message != null) {
            if (message.contains("INDEX_STALE") || message.contains("INDEX_MISSING") || message.contains("PROJECT_BINDING_CHANGED")) {
                return ExitCode.STALE;
            }
            if (message.contains("INDEX_BUSY") || message.contains("INDEX_TIMEOUT") || message.contains("SEMANTIC_TIMEOUT")
                    || message.contains("DEPTH_LIMIT") || message.contains("EDGE_LIMIT") || message.contains("TIME_LIMIT")) {
                return ExitCode.RESOURCE;
            }
        }
        return ExitCode.INTERNAL;
    }

    // 作用：把异常包装为可抛出的业务失败；逻辑：复用退出码映射构造失败对象。
    public CliFailure toFailure(Throwable cause) {
        return new CliFailure(exitCode(cause));
    }
}
