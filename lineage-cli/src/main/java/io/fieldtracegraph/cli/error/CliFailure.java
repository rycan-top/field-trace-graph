package io.fieldtracegraph.cli.error;

// 作用：表示可预期的命令业务失败。
// 使用场景：命令内部主动中止并携带退出码。

public final class CliFailure extends RuntimeException {
    private final int exitCode;

    // 作用：构造业务失败；逻辑：关闭堆栈填充并保存退出码。
    public CliFailure(int exitCode) {
        super(null, null, false, false);
        this.exitCode = exitCode;
    }

    // 作用：读取退出码；逻辑：返回构造时保存的进程退出码。
    public int exitCode() {
        return exitCode;
    }

    // 作用：由任意异常构造业务失败；逻辑：委托异常映射器换算退出码。
    public static CliFailure from(Throwable cause) {
        return new CliExceptionMapper().toFailure(cause);
    }
}
