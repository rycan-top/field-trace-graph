package io.fieldtracegraph.cli.controller;

import io.fieldtracegraph.cli.application.MethodSearchService;
import io.fieldtracegraph.cli.config.SourceScanOptionsFactory;
import io.fieldtracegraph.cli.error.CliFailure;
import io.fieldtracegraph.cli.view.ResultView;
import io.fieldtracegraph.core.model.CommandResult;
import io.fieldtracegraph.core.model.MethodsResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

// 作用：方法搜索命令。
// 使用场景：ftg methods --name 查找方法签名以用于后续追踪。

@Command(name = "methods", description = "Search method and constructor candidates")
public class MethodsCommand implements Runnable {
    private final MethodSearchService methodSearchService;

    private final ResultView resultView;

    // 作用：注入方法搜索依赖；逻辑：保存搜索服务与结果渲染器。
    public MethodsCommand(MethodSearchService methodSearchService, ResultView resultView) {
        this.methodSearchService = methodSearchService;
        this.resultView = resultView;
    }
    @Option(names = "--project", description = "Project root; defaults to the current directory")
    private String project;

    @Option(names = "--name", required = true, description = "Class or method name fragment")
    private String name;

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

    @Option(names = "--include-accessors", defaultValue = "false",
            description = "Include getter/setter symbols; hidden by default")
    private boolean includeAccessors;

    // 作用：执行方法搜索；逻辑：组装扫描与分页参数后调用搜索服务并按格式输出。
    @Override
    public void run() {
        try {
            MethodsResult result = methodSearchService.search(
                    project,
                    name,
                    offset,
                    limit,
                    includeAccessors,
                    noSync,
                    SourceScanOptionsFactory.create(
                            languageLevel, includeTests, generatedSources, classpathEntries, classpathFile)
            );
            System.out.println(resultView.render(CommandResult.success("methods", result), format));
        } catch (Exception e) {
            System.out.println(resultView.render(CommandResult.failure("methods", "METHODS_FAILED", e.getMessage()), format));
            throw CliFailure.from(e);
        }
    }
}
