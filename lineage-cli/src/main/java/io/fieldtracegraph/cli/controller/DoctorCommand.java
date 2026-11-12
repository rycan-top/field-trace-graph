package io.fieldtracegraph.cli.controller;

import io.fieldtracegraph.cli.application.RuntimeDoctorService;
import io.fieldtracegraph.cli.error.CliFailure;
import io.fieldtracegraph.cli.view.ResultView;
import io.fieldtracegraph.core.model.CommandResult;
import io.fieldtracegraph.core.model.DoctorResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

// 作用：环境体检命令。
// 使用场景：ftg doctor 检查 JDK、权限与配置是否满足运行要求。

@Command(name = "doctor", description = "Check the local runtime environment")
public class DoctorCommand implements Runnable {
    private final RuntimeDoctorService runtimeDoctorService;

    private final ResultView resultView;

    // 作用：注入体检依赖；逻辑：保存环境检查服务与结果渲染器。
    public DoctorCommand(RuntimeDoctorService runtimeDoctorService, ResultView resultView) {
        this.runtimeDoctorService = runtimeDoctorService;
        this.resultView = resultView;
    }
    @Option(names = "--project", description = "Project root; defaults to the current directory")
    private String project;

    @Option(names = "--format", defaultValue = "text", description = "Output format: text or json")
    private String format;

    // 作用：执行环境体检；逻辑：调用检查服务并输出结果，异常时输出失败并转换为退出码。
    @Override
    public void run() {
        try {
            DoctorResult result = runtimeDoctorService.check(project);
            System.out.println(resultView.render(CommandResult.success("doctor", result), format));
        } catch (Exception e) {
            System.out.println(resultView.render(CommandResult.failure("doctor", "DOCTOR_FAILED", e.getMessage()), format));
            throw CliFailure.from(e);
        }
    }
}
