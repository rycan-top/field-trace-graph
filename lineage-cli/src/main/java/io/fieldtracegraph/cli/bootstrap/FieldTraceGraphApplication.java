package io.fieldtracegraph.cli.bootstrap;

import io.fieldtracegraph.cli.config.LineageBeanConfiguration;
import picocli.CommandLine;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

// 作用：程序唯一入口，初始化标准输出编码并执行 picocli 命令。
// 使用场景：通过 java -jar 或 ftg 别名启动任意子命令。

public final class FieldTraceGraphApplication {
    // 作用：启动命令行程序；逻辑：固定标准输出编码后构建 picocli 命令并以其退出码结束进程。
    public static void main(String[] args) {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        CommandLine commandLine = new LineageBeanConfiguration().createCommandLine();
        int exitCode = commandLine.execute(args);
        System.exit(exitCode);
    }
}
