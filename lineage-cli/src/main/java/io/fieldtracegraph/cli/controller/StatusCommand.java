package io.fieldtracegraph.cli.controller;

import io.fieldtracegraph.cli.application.IndexStatusService;
import io.fieldtracegraph.cli.config.SourceScanOptionsFactory;
import io.fieldtracegraph.cli.error.CliFailure;
import io.fieldtracegraph.cli.error.ExitCode;
import io.fieldtracegraph.cli.view.ResultView;
import io.fieldtracegraph.core.model.CommandResult;
import io.fieldtracegraph.core.model.Diagnostic;
import io.fieldtracegraph.core.model.IndexQuality;
import io.fieldtracegraph.core.model.IndexStatusResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// 作用：索引状态与质量门禁命令。
// 使用场景：ftg status --quality 查看精确率、候选率与边界密度。

@Command(name = "status", description = "Show the published index state")
public class StatusCommand implements Runnable {
    private final IndexStatusService indexStatusService;

    private final ResultView resultView;

    // 作用：注入状态检查依赖；逻辑：保存状态服务与结果渲染器。
    public StatusCommand(IndexStatusService indexStatusService, ResultView resultView) {
        this.indexStatusService = indexStatusService;
        this.resultView = resultView;
    }
    @Option(names = "--project", description = "Project root; defaults to the current directory")
    private String project;

    @Option(names = "--format", defaultValue = "text", description = "Output format: text or json")
    private String format;

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

    @Option(names = "--quality", defaultValue = "false",
            description = "Include exact/candidate ratios and top open-boundary reasons")
    private boolean quality;

    @Option(names = "--fail-on-boundary-density",
            description = "Fail when open boundaries per 1000 methods exceed this value")
    private Double failOnBoundaryDensity;

    @Option(names = "--fail-on-reason", split = ",",
            description = "Fail when this canonical boundary reason is present; repeat or comma-separate")
    private List<String> failOnReasons = List.of();

    // 作用：执行状态检查；逻辑：校验项目绑定后读取状态与质量，命中门禁时输出违规并返回质量退出码。
    @Override
    public void run() {
        try {
            indexStatusService.verifyBinding(project);
            boolean includeQuality = quality
                    || failOnBoundaryDensity != null
                    || !failOnReasons.isEmpty();
            IndexStatusResult result = indexStatusService.inspect(
                    project,
                    SourceScanOptionsFactory.create(
                            languageLevel, includeTests, generatedSources, classpathEntries, classpathFile),
                    includeQuality
            );
            List<Diagnostic> violations = gateViolations(result.quality());
            if (!violations.isEmpty()) {
                System.out.println(resultView.render(
                        CommandResult.partial("status", result, violations), format));
                throw new CliFailure(ExitCode.QUALITY_GATE);
            }
            System.out.println(resultView.render(CommandResult.success("status", result), format));
        } catch (CliFailure e) {
            throw e;
        } catch (Exception e) {
            System.out.println(resultView.render(CommandResult.failure("status", "STATUS_FAILED", e.getMessage()), format));
            throw CliFailure.from(e);
        }
    }

    // 作用：计算质量门禁违规；逻辑：检查边界密度阈值与指定原因码是否出现，生成诊断列表。
    private List<Diagnostic> gateViolations(IndexQuality quality) {
        if (quality == null) {
            return List.of();
        }
        List<Diagnostic> violations = new ArrayList<>();
        if (failOnBoundaryDensity != null
                && quality.openBoundaryDensityPer1000Methods() > failOnBoundaryDensity) {
            violations.add(new Diagnostic(
                    "BOUNDARY_DENSITY_EXCEEDED",
                    String.format(
                            Locale.ROOT,
                            "Open boundary density %.1f/1000 methods exceeds the %.1f limit (%d open boundaries)",
                            quality.openBoundaryDensityPer1000Methods(),
                            failOnBoundaryDensity,
                            quality.openBoundaryCount()
                    ),
                    null, null, false, true
            ));
        }
        for (String raw : failOnReasons) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            int count = quality.boundaryCount(raw);
            if (count > 0) {
                violations.add(new Diagnostic(
                        "BOUNDARY_REASON_PRESENT",
                        "Boundary reason " + raw.trim().toUpperCase(Locale.ROOT)
                                + " is present " + count + " time(s)",
                        null, null, false, true
                ));
            }
        }
        return violations;
    }
}
