package io.fieldtracegraph.cli.controller;

import io.fieldtracegraph.cli.application.ProjectInitializationService;
import io.fieldtracegraph.cli.config.SourceScanOptionsFactory;
import io.fieldtracegraph.cli.error.CliFailure;
import io.fieldtracegraph.cli.view.ResultView;
import io.fieldtracegraph.core.model.CommandResult;
import io.fieldtracegraph.core.model.IndexStatusResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

// 作用：项目初始化命令。
// 使用场景：首次使用 ftg init 创建索引目录并完成首次索引。

@Command(name = "init", description = "Initialize the project and build the full local lineage graph")
public class InitCommand implements Runnable {
    private final ProjectInitializationService projectInitializationService;

    private final ResultView resultView;

    // 作用：注入初始化依赖；逻辑：保存初始化服务与结果渲染器。
    public InitCommand(ProjectInitializationService projectInitializationService, ResultView resultView) {
        this.projectInitializationService = projectInitializationService;
        this.resultView = resultView;
    }
    @Option(names = "--project", description = "Project root; defaults to the current directory")
    private String project;

    @Option(names = "--format", defaultValue = "text", description = "Output format: text or json")
    private String format;

    @Option(names = "--lock-timeout", defaultValue = "30", description = "Seconds to wait for the project write lock")
    private long lockTimeoutSeconds;

    @Option(names = "--index-timeout", defaultValue = "300", description = "Seconds allowed for the initial index")
    private long indexTimeoutSeconds;

    @Option(names = "--language-level", description = "Override the analysed Java language level")
    private String languageLevel;

    @Option(names = "--include-tests", defaultValue = "false", description = "Include test source roots")
    private boolean includeTests;

    @Option(names = "--generated-sources", split = ",",
            description = "Explicit generated-source root; repeat or comma-separate for multiple roots")
    private List<String> generatedSources = List.of();

    @Option(names = "--classpath", split = ",",
            description = "Explicit classpath entry (JAR or class directory); repeat or comma-separate")
    private List<String> classpathEntries = List.of();

    @Option(names = "--classpath-file",
            description = "File with classpath entries (one per line or ':'/';' separated)")
    private String classpathFile;

    // 作用：执行初始化；逻辑：换算超时后调用初始化服务，成功或失败均按选定格式输出。
    @Override
    public void run() {
        try {
            IndexStatusResult result = projectInitializationService.initialize(
                    project,
                    lockTimeoutSeconds * 1000L,
                    indexTimeoutSeconds * 1000L,
                    SourceScanOptionsFactory.create(
                            languageLevel, includeTests, generatedSources, classpathEntries, classpathFile)
            );
            System.out.println(resultView.render(CommandResult.success("init", result), format));
        } catch (Exception e) {
            System.out.println(resultView.render(CommandResult.failure("init", "INIT_FAILED", e.getMessage()), format));
            throw CliFailure.from(e);
        }
    }
}
