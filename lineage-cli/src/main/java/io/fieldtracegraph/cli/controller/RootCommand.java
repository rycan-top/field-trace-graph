package io.fieldtracegraph.cli.controller;

import io.fieldtracegraph.cli.bootstrap.VersionInfo;
import io.fieldtracegraph.cli.bootstrap.VersionProvider;
import io.fieldtracegraph.core.model.CommandResult;
import io.fieldtracegraph.cli.view.ResultView;
import picocli.CommandLine.Command;

// 作用：根命令，提供帮助与版本信息。
// 使用场景：直接执行 ftg 或 ftg --help。

@Command(
        name = "field-trace-graph",
        versionProvider = VersionProvider.class,
        mixinStandardHelpOptions = true
)
public class RootCommand implements Runnable {
    private final ResultView resultView;

    // 作用：注入结果视图；逻辑：保存渲染器供根命令输出使用。
    public RootCommand(ResultView resultView) {
        this.resultView = resultView;
    }
    // 作用：输出根命令信息；逻辑：以文本格式打印名称与版本。
    @Override
    public void run() {
        System.out.println(resultView.render(
                CommandResult.success("root", "field-trace-graph " + VersionInfo.version()),
                "text"
        ));
    }
}
