package io.fieldtracegraph.cli.controller;

import io.fieldtracegraph.cli.application.ProjectIndexService;
import io.fieldtracegraph.cli.config.SourceScanOptionsFactory;
import io.fieldtracegraph.cli.error.CliFailure;
import io.fieldtracegraph.cli.view.ResultView;
import io.fieldtracegraph.core.model.CommandResult;
import io.fieldtracegraph.core.model.IndexStatusResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

// 作用：构建或刷新本地索引的命令。
// 使用场景：ftg index，支持全量重建、classpath 与生成源码参数。

@Command(name = "index", description = "Build or refresh the local field-lineage index")
public class IndexCommand implements Runnable {
    private final ProjectIndexService projectIndexService;

    private final ResultView resultView;

    // 作用：注入索引依赖；逻辑：保存索引服务与结果渲染器。
    public IndexCommand(ProjectIndexService projectIndexService, ResultView resultView) {
        this.projectIndexService = projectIndexService;
        this.resultView = resultView;
    }
    @Option(names = "--project", description = "Project root; defaults to the current directory")
    private String project;

    @Option(names = "--full", defaultValue = "false", description = "Force a full rebuild")
    private boolean full;

    @Option(names = "--format", defaultValue = "text", description = "Output format: text or json")
    private String format;

    @Option(names = "--lock-timeout", defaultValue = "30", description = "Seconds to wait for the project write lock")
    private long lockTimeoutSeconds;

    @Option(names = "--index-timeout", defaultValue = "300", description = "Seconds allowed for one index attempt")
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

    // 作用：执行索引；逻辑：把秒级超时换算为毫秒，调用同步服务并按格式输出结果或失败信息。
    @Override
    public void run() {
        try {
            IndexStatusResult result = projectIndexService.synchronize(
                    project,
                    full,
                    lockTimeoutSeconds * 1000L,
                    indexTimeoutSeconds * 1000L,
                    SourceScanOptionsFactory.create(
                            languageLevel, includeTests, generatedSources, classpathEntries, classpathFile)
            );
            System.out.println(resultView.render(CommandResult.success("index", result), format));
        } catch (Exception e) {
            System.out.println(resultView.render(CommandResult.failure("index", "INDEX_FAILED", e.getMessage()), format));
            throw CliFailure.from(e);
        }
    }
}
