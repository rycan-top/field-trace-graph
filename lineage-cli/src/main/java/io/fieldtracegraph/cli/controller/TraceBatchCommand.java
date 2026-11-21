package io.fieldtracegraph.cli.controller;

import io.fieldtracegraph.cli.application.BatchTraceService;
import io.fieldtracegraph.cli.config.SourceScanOptionsFactory;
import io.fieldtracegraph.cli.error.CliFailure;
import io.fieldtracegraph.cli.view.TextResultView;
import io.fieldtracegraph.core.model.CommandResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;

// 作用：批量追踪命令。
// 使用场景：对同一索引的大批 method/target 做回归审计，避免逐条启动 JVM。

@Command(name = "trace-batch", description = "Trace many method/target pairs against one index")
public class TraceBatchCommand implements Runnable {
    private final BatchTraceService batchTraceService;

    // 作用：注入批量追踪服务；逻辑：保存应用服务引用。
    public TraceBatchCommand(BatchTraceService batchTraceService) {
        this.batchTraceService = batchTraceService;
    }

    @Option(names = "--project", description = "Project root; defaults to the current directory")
    private String project;

    @Option(names = "--cases", required = true, description = "TSV file with method<TAB>target per line")
    private String casesFile;

    @Option(names = "--out", required = true, description = "NDJSON output file, appended")
    private String outFile;

    @Option(names = "--text-dir", description = "Also write readable text for non-COMPLETE cases")
    private String textDir;

    @Option(names = "--text-max", defaultValue = "200", description = "Maximum readable text files to write")
    private int textMax;

    @Option(names = "--direction", defaultValue = "both", description = "Trace direction: in, out, or both")
    private String direction;

    @Option(names = "--no-sync", defaultValue = "true", description = "Do not update the index")
    private boolean noSync;

    @Option(names = "--allow-stale", defaultValue = "false", description = "Allow reading the previous graph")
    private boolean allowStale;

    @Option(names = "--query-timeout", defaultValue = "120", description = "Per-case timeout in seconds")
    private long queryTimeoutSeconds;

    @Option(names = "--include-dependencies", defaultValue = "false",
            description = "Include control and call-input dependency edges")
    private boolean includeDependencies;

    @Option(names = "--resume", defaultValue = "false", description = "Skip cases already present in --out")
    private boolean resume;

    @Option(names = "--limit", defaultValue = "0", description = "Maximum cases to execute this run")
    private int limit;

    @Option(names = "--language-level", description = "Override the analysed Java language level")
    private String languageLevel;

    @Option(names = "--include-tests", defaultValue = "false", description = "Include test source roots")
    private boolean includeTests;

    @Option(names = "--generated-sources", split = ",",
            description = "Explicit generated-source root; repeat or comma-separate")
    private List<String> generatedSources = List.of();

    @Option(names = "--classpath", split = ",",
            description = "Explicit classpath entry; repeat or comma-separate")
    private List<String> classpathEntries = List.of();

    @Option(names = "--classpath-file", description = "File with classpath entries")
    private String classpathFile;

    // 作用：执行批量追踪；逻辑：解析用例文件后逐条查询并打印汇总。
    @Override
    public void run() {
        try {
            List<BatchTraceService.Case> cases = BatchTraceService.readCases(Path.of(casesFile));
            BatchTraceService.Summary summary = batchTraceService.run(
                    project,
                    cases,
                    Path.of(outFile),
                    textDir == null || textDir.isBlank() ? null : Path.of(textDir),
                    textMax,
                    direction,
                    noSync,
                    allowStale,
                    queryTimeoutSeconds * 1000L,
                    SourceScanOptionsFactory.create(
                            languageLevel, includeTests, generatedSources, classpathEntries, classpathFile),
                    includeDependencies,
                    resume,
                    limit,
                    result -> new TextResultView().render(CommandResult.success("trace", result))
            );
            System.out.println("Batch trace: project=" + summary.root()
                    + " declared=" + summary.declaredCases()
                    + " executed=" + summary.executedCases()
                    + " written=" + summary.written()
                    + " text=" + summary.textWritten());
        } catch (Exception e) {
            throw CliFailure.from(e);
        }
    }
}
