package io.fieldtracegraph.cli.controller;

import io.fieldtracegraph.cli.application.TargetDiscoveryService;
import io.fieldtracegraph.cli.config.SourceScanOptionsFactory;
import io.fieldtracegraph.cli.error.CliFailure;
import io.fieldtracegraph.cli.view.ResultView;
import io.fieldtracegraph.core.model.CommandResult;
import io.fieldtracegraph.core.model.TargetsResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

// 作用：目标发现命令。
// 使用场景：ftg targets 列出方法内可追踪的字段与表达式。

@Command(name = "targets", description = "List queryable value targets for a method")
public class TargetsCommand implements Runnable {
    private final TargetDiscoveryService targetDiscoveryService;

    private final ResultView resultView;

    // 作用：注入目标发现依赖；逻辑：保存发现服务与结果渲染器。
    public TargetsCommand(TargetDiscoveryService targetDiscoveryService, ResultView resultView) {
        this.targetDiscoveryService = targetDiscoveryService;
        this.resultView = resultView;
    }
    @Option(names = "--project", description = "Project root; defaults to the current directory")
    private String project;

    @Option(names = "--method", required = true, description = "Method signature from methods; a stable method id is also accepted")
    private String methodSelector;

    @Option(names = "--format", defaultValue = "text", description = "Output format: text or json")
    private String format;

    @Option(names = "--no-sync", defaultValue = "false", description = "Do not update the index")
    private boolean noSync;

    @Option(names = "--offset", defaultValue = "0", description = "Result offset")
    private int offset;

    @Option(names = "--limit", defaultValue = "30", description = "Result limit")
    private int limit;

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

    // 作用：执行目标发现；逻辑：组装参数后调用发现服务并按格式输出候选目标。
    @Override
    public void run() {
        try {
            TargetsResult result = targetDiscoveryService.discover(
                    project,
                    methodSelector,
                    noSync,
                    offset,
                    limit,
                    SourceScanOptionsFactory.create(
                            languageLevel, includeTests, generatedSources, classpathEntries, classpathFile)
            );
            System.out.println(resultView.render(CommandResult.success("targets", result), format));
        } catch (Exception e) {
            System.out.println(resultView.render(CommandResult.failure("targets", "TARGETS_FAILED", e.getMessage()), format));
            throw CliFailure.from(e);
        }
    }
}
