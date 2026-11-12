package io.fieldtracegraph.cli.error;

import picocli.CommandLine;

// 作用：接管 picocli 执行期间的异常处理。
// 使用场景：参数错误、业务失败与未捕获异常的统一处理。

/**
 * Applies the same stable exit-code contract in production and acceptance
 * tests by making the composition root responsible for installing it.
 */
public final class CliExecutionExceptionHandler implements CommandLine.IExecutionExceptionHandler {
    private final CliExceptionMapper exceptionMapper = new CliExceptionMapper();

    // 作用：处理命令执行异常；逻辑：业务失败直接返回其退出码，其余打印错误并映射退出码。
    @Override
    public int handleExecutionException(Exception exception, CommandLine commandLine, CommandLine.ParseResult parseResult) {
        if (exception instanceof CliFailure failure) {
            return failure.exitCode();
        }
        commandLine.getErr().println("Error: " + exception.getMessage());
        return exceptionMapper.exitCode(exception);
    }
}
