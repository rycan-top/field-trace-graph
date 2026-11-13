package io.fieldtracegraph.cli.bootstrap;

import picocli.CommandLine;

// 作用：向 picocli 提供版本行。
// 使用场景：`ftg --version` 与帮助信息中的版本输出。
public final class VersionProvider implements CommandLine.IVersionProvider {
    // 作用：生成版本行；逻辑：拼接 CLI 名称、构建版本与 schema 版本。
    @Override
    public String[] getVersion() {
        return new String[]{
                "field-trace-graph " + VersionInfo.version() + " (schema-version 1)"
        };
    }
}
