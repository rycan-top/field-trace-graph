package io.fieldtracegraph.cli.controller;

import picocli.CommandLine.Command;

// 作用：证据相关子命令的容器。
// 使用场景：ftg evidence 下挂载 import 与 diff。

@Command(
        name = "evidence",
        description = "Import and inspect runtime evidence",
        mixinStandardHelpOptions = true
)
public class EvidenceCommand implements Runnable {
    // 作用：作为命令分组占位；逻辑：不执行实际逻辑，仅承载 import 与 diff 子命令。
    @Override
    public void run() {
    }
}
