package io.fieldtracegraph.cli.controller;

import io.fieldtracegraph.cli.application.SourceEvidenceService;
import io.fieldtracegraph.cli.error.CliFailure;
import io.fieldtracegraph.cli.view.ResultView;
import io.fieldtracegraph.core.model.CommandResult;
import io.fieldtracegraph.core.model.SourceScanOptions;
import io.fieldtracegraph.core.model.SourceResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

// 作用：源码证据查询命令。
// 使用场景：ftg source 定位某条结论对应的文件与行号。

@Command(name = "source", description = "Read source lines from the indexed file")
public class SourceCommand implements Runnable {
    private final SourceEvidenceService sourceEvidenceService;

    private final ResultView resultView;

    // 作用：注入源码证据依赖；逻辑：保存证据服务与结果渲染器。
    public SourceCommand(SourceEvidenceService sourceEvidenceService, ResultView resultView) {
        this.sourceEvidenceService = sourceEvidenceService;
        this.resultView = resultView;
    }
    @Option(names = "--project", description = "Project root; defaults to the current directory")
    private String project;

    @Option(names = "--file", required = true, description = "Relative source file path")
    private String relativePath;

    @Option(names = "--start", defaultValue = "1", description = "Start line")
    private int startLine;

    @Option(names = "--end", defaultValue = "20", description = "End line")
    private int endLine;

    @Option(names = "--format", defaultValue = "text", description = "Output format: text or json")
    private String format;

    @Option(names = "--language-level", description = "Override the analysed Java language level")
    private String languageLevel;

    @Option(names = "--include-tests", defaultValue = "false", description = "Include test source roots")
    private boolean includeTests;

    // 作用：执行源码查询；逻辑：组装扫描选项后读取指定行范围并按格式输出。
    @Override
    public void run() {
        try {
            SourceResult result = sourceEvidenceService.read(project, relativePath, startLine, endLine, new SourceScanOptions(languageLevel, includeTests));
            System.out.println(resultView.render(CommandResult.success("source", result), format));
        } catch (Exception e) {
            System.out.println(resultView.render(CommandResult.failure("source", "SOURCE_FAILED", e.getMessage()), format));
            throw CliFailure.from(e);
        }
    }
}
