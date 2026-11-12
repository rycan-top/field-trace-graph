package io.fieldtracegraph.cli.log;

// 作用：把日志统一写到标准错误。
// 使用场景：保证标准输出中的 JSON 不被日志污染。

/**
 * Minimal stderr logger. stdout is reserved for machine-readable command
 * results, so all progress and diagnostics go to stderr.
 */
public final class CliLog {
    // 作用：阻止日志工具类被实例化；逻辑：私有空构造器。
    private CliLog() {
    }

    // 作用：输出提示日志；逻辑：向标准错误写入带 info 前缀的行。
    public static void info(String message) {
        System.err.println("[info] " + message);
    }

    // 作用：输出告警日志；逻辑：向标准错误写入带 warn 前缀的行。
    public static void warn(String message) {
        System.err.println("[warn] " + message);
    }

    // 作用：输出错误日志；逻辑：向标准错误写入带 error 前缀的行。
    public static void error(String message) {
        System.err.println("[error] " + message);
    }
}
