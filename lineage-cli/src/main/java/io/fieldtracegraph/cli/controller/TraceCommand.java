package io.fieldtracegraph.cli.controller;

import io.fieldtracegraph.cli.application.TraceApplicationService;
import io.fieldtracegraph.cli.config.SourceScanOptionsFactory;
import io.fieldtracegraph.cli.error.CliFailure;
import io.fieldtracegraph.cli.error.ExitCode;
import io.fieldtracegraph.cli.view.ResultView;
import io.fieldtracegraph.core.model.CommandResult;
import io.fieldtracegraph.core.model.Diagnostic;
import io.fieldtracegraph.core.model.TraceResult;
import io.fieldtracegraph.core.query.CallScopeMode;
import io.fieldtracegraph.core.query.TraceCompleteness;
import io.fieldtracegraph.core.query.TraceQuality;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

// 作用：字段血缘追踪命令。
// 使用场景：ftg trace 按方向、范围与预算追踪字段来源与流向。

@Command(name = "trace", description = "Trace field lineage for a target in a method")
public class TraceCommand implements Runnable {
    private final TraceApplicationService traceApplicationService;

    private final ResultView resultView;

    // 作用：注入追踪依赖；逻辑：保存追踪应用服务与结果渲染器。
    public TraceCommand(TraceApplicationService traceApplicationService, ResultView resultView) {
        this.traceApplicationService = traceApplicationService;
        this.resultView = resultView;
    }
    @Option(names = "--project", description = "Project root; defaults to the current directory")
    private String project;

    @Option(names = "--method", required = true, description = "Method signature from methods; a stable method id is also accepted")
    private String methodSelector;

    @Option(names = "--target", required = true, description = "Target expression, such as result.status")
    private String targetExpression;

    @Option(names = "--direction", defaultValue = "both", description = "Trace direction: in, out, or both")
    private String direction;

    @Option(
            names = "--call-scope",
            defaultValue = "effective",
            description = "Call scope: effective (selected method and callees; default) or full (also include callers)"
    )
    private String callScope;

    @Option(names = "--format", defaultValue = "text", description = "Output format: text or json")
    private String format;

    @Option(names = "--no-sync", defaultValue = "false", description = "Do not update the index")
    private boolean noSync;

    @Option(names = "--allow-stale", defaultValue = "false", description = "Allow reading the previous graph")
    private boolean allowStale;

    @Option(names = "--max-depth", defaultValue = "-1",
            description = "Maximum traversal depth; -1 uses the field/object budget policy")
    private int maxDepth;

    @Option(names = "--max-edges", defaultValue = "-1",
            description = "Maximum traversal edges; -1 uses the field/object budget policy")
    private int maxEdges;

    @Option(names = "--query-timeout", defaultValue = "0", description = "Query timeout in seconds; 0 disables the timeout")
    private long queryTimeoutSeconds;

    @Option(
            names = "--include-dependencies",
            defaultValue = "false",
            description = "Include control and call-input dependency edges in addition to field value flow"
    )
    private boolean includeDependencies;

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

    @Option(names = "--max-weak-hops", defaultValue = "-1",
            description = "Fail when the trace exposes more weak hops than this value; -1 disables the gate")
    private int maxWeakHops;

    @Option(
            names = "--allow-resolution",
            defaultValue = "exact",
            description = "Accepted resolution tier: exact or candidate"
    )
    private String allowResolution;

    @Option(
            names = "--fail-on-candidate",
            defaultValue = "false",
            description = "Return a non-zero exit code when candidate evidence is present"
    )
    private boolean failOnCandidate;

    @Option(names = "--strict-contracts",
            defaultValue = "false",
            description = "Fail when a contracts.yml entry is expired, incomplete, or matches no edge")
    private boolean strictContracts;

    // 作用：执行追踪；逻辑：调用应用服务并附加质量策略，输出结果后按门禁决定是否以非零码结束。
    @Override
    public void run() {
        TraceResult result;
        boolean allowCandidate = allowCandidate();
        try {
            result = traceApplicationService.execute(
                    project,
                    methodSelector,
                    targetExpression,
                    direction,
                    noSync,
                    allowStale,
                    maxDepth,
                    maxEdges,
                    queryTimeoutSeconds * 1000L,
                    SourceScanOptionsFactory.create(
                            languageLevel, includeTests, generatedSources, classpathEntries, classpathFile),
                    CallScopeMode.fromCliValue(callScope),
                    includeDependencies,
                    strictContracts
            ).withQualityPolicy(allowCandidate, failOnCandidate);
        } catch (Exception e) {
            System.out.println(resultView.render(CommandResult.failure("trace", "TRACE_FAILED", e.getMessage()), format));
            throw CliFailure.from(e);
        }
        CommandResult commandResult = commandResult(result, allowCandidate, failOnCandidate);
        System.out.println(resultView.render(commandResult, format));
        if (!commandResult.complete()) {
            throw new CliFailure(exitCodeFor(result));
        }
    }

    // 作用：组装命令结果；逻辑：按快照一致性、截断、过期、弱跳与候选证据等条件判定成功或部分成功。
    private CommandResult commandResult(
            TraceResult result,
            boolean allowCandidate,
            boolean failOnCandidate
    ) {
        if (!result.snapshotConsistent()) {
            return CommandResult.partial("trace", result, List.of(
                    new Diagnostic("SNAPSHOT_INCONSISTENT", "Trace evidence does not match one index snapshot", null, null, false, true)
            ));
        }
        if (result.truncated()) {
            return CommandResult.partial("trace", result, List.of(
                    new Diagnostic("TRUNCATED", String.join(";", result.truncationReasons()), null, null, false, true)
            ));
        }
        if ("STALE".equals(result.freshness())) {
            return CommandResult.partial("trace", result, List.of(
                    new Diagnostic("INDEX_STALE", "The published index does not match the current source tree", null, null, false, true)
            ));
        }
        TraceCompleteness completeness = TraceCompleteness.from(result);
        if (completeness.status() == TraceCompleteness.Status.PARTIAL
                || completeness.status() == TraceCompleteness.Status.INSUFFICIENT) {
            return CommandResult.partial("trace", result, List.of(
                    new Diagnostic(
                            "TRACE_COMPLETENESS_" + completeness.status(),
                            "Trace connectivity is not complete: " + completeness.summary(),
                            null,
                            null,
                            false,
                            true
                    )
            ));
        }
        TraceQuality quality = TraceQuality.from(result, allowCandidate, failOnCandidate);
        if (maxWeakHops >= 0 && quality.weakHopCount() > maxWeakHops) {
            return CommandResult.partial("trace", result, List.of(
                    new Diagnostic(
                            "WEAK_HOP_BUDGET_EXCEEDED",
                            "Trace exposes " + quality.weakHopCount()
                                    + " weak hops, above the --max-weak-hops limit of " + maxWeakHops
                                    + ": " + quality.summary(),
                            null,
                            null,
                            false,
                            true
                    )
            ));
        }
        if (failOnCandidate && quality.candidateEdges() > 0) {
            return CommandResult.partial("trace", result, List.of(
                    new Diagnostic(
                            "CANDIDATE_EVIDENCE_PRESENT",
                            "Candidate evidence is not allowed by --fail-on-candidate: "
                                    + quality.summary(),
                            null,
                            null,
                            false,
                            true
                    )
            ));
        }
        if (quality.insufficient()) {
            return CommandResult.partial("trace", result, List.of(
                    new Diagnostic(
                            "TRACE_QUALITY_INSUFFICIENT",
                            "Unresolved evidence is " + quality.summary(),
                            null,
                            null,
                            false,
                            true
                    )
            ));
        }
        if (quality.partial()) {
            return CommandResult.partial("trace", result, List.of(
                    new Diagnostic(
                            "TRACE_QUALITY_PARTIAL",
                            "Trace contains incomplete resolution: " + quality.summary(),
                            null,
                            null,
                            false,
                            true
                    )
            ));
        }
        if (result.edges().isEmpty()) {
            List<Diagnostic> diagnostics = result.noMatchReason() == null
                    ? List.of()
                    : List.of(new Diagnostic(
                    result.noMatchReason().code(),
                    result.noMatchReason().message(),
                    null,
                    null,
                    false,
                    false
            ));
            return new CommandResult(1, "trace", "NO_MATCH", true, result, diagnostics);
        }
        return CommandResult.success("trace", result);
    }

    // 作用：计算失败退出码；逻辑：截断归资源错误，未解析或过期归索引陈旧，弱跳超限归质量门禁。
    private int exitCodeFor(TraceResult result) {
        if (result.truncated()) {
            return ExitCode.RESOURCE;
        }
        TraceQuality quality = TraceQuality.from(
                result,
                result.allowCandidate(),
                result.failOnCandidate()
        );
        TraceCompleteness completeness = TraceCompleteness.from(result);
        if (completeness.status() == TraceCompleteness.Status.PARTIAL
                || completeness.status() == TraceCompleteness.Status.INSUFFICIENT) {
            return ExitCode.STALE;
        }
        if (quality.insufficient()) {
            return ExitCode.STALE;
        }
        if (maxWeakHops >= 0 && quality.weakHopCount() > maxWeakHops) {
            return ExitCode.QUALITY_GATE;
        }
        if ("STALE".equals(result.freshness()) || quality.partial()) {
            return ExitCode.STALE;
        }
        return ExitCode.OK;
    }

    // 作用：解析候选证据策略；逻辑：exact 拒绝候选、candidate 接受，其他取值报参数错误。
    private boolean allowCandidate() {
        if (allowResolution == null || allowResolution.isBlank()) {
            return false;
        }
        return switch (allowResolution.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "exact" -> false;
            case "candidate" -> true;
            default -> throw new IllegalArgumentException(
                    "--allow-resolution must be exact or candidate"
            );
        };
    }
}
