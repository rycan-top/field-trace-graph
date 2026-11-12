package io.fieldtracegraph.cli.controller;

import io.fieldtracegraph.cli.application.EvidenceDiffService;
import io.fieldtracegraph.cli.application.EvidenceFileReader;
import io.fieldtracegraph.cli.dto.EvidenceImportItem;
import io.fieldtracegraph.cli.error.CliFailure;
import io.fieldtracegraph.cli.error.ExitCode;
import io.fieldtracegraph.cli.view.ResultView;
import io.fieldtracegraph.core.model.CommandResult;
import io.fieldtracegraph.core.model.Diagnostic;
import io.fieldtracegraph.core.model.EvidenceDiffResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;

// 作用：证据差异命令。
// 使用场景：ftg evidence diff 比较两次证据集合的变化。

@Command(
        name = "diff",
        description = "Compare runtime evidence identities with the static graph",
        mixinStandardHelpOptions = true
)
public class EvidenceDiffCommand implements Runnable {
    private final EvidenceDiffService evidenceDiffService;
    private final ResultView resultView;
    private final EvidenceFileReader evidenceFileReader = new EvidenceFileReader();

    // 作用：注入证据差异依赖；逻辑：保存差异服务与结果渲染器。
    public EvidenceDiffCommand(
            EvidenceDiffService evidenceDiffService,
            ResultView resultView
    ) {
        this.evidenceDiffService = evidenceDiffService;
        this.resultView = resultView;
    }

    @Option(names = "--project", description = "Project root; defaults to the current directory")
    private String project;

    @Option(names = "--file", required = true, description = "Runtime evidence JSON file")
    private Path file;

    @Option(names = "--format", defaultValue = "text", description = "Output format: text or json")
    private String format;

    @Option(
            names = "--fail-on-missing",
            defaultValue = "false",
            description = "Return a non-zero exit code when a runtime edge is absent from the static graph"
    )
    private boolean failOnMissing;

    // 作用：执行证据差异比较；逻辑：读取证据后比对静态图，缺失且开启门禁时输出部分结果并返回过期退出码。
    @Override
    public void run() {
        try {
            List<EvidenceImportItem> items = evidenceFileReader.read(file);
            EvidenceDiffResult result = evidenceDiffService.diff(project, items);
            CommandResult commandResult = result.missingCount() > 0 && failOnMissing
                    ? CommandResult.partial("evidence-diff", result, List.of(
                    new Diagnostic(
                            "RUNTIME_EVIDENCE_DRIFT",
                            "Runtime evidence contains " + result.missingCount()
                                    + " logical edge(s) absent from the static graph",
                            null,
                            null,
                            false,
                            true
                    )
            ))
                    : CommandResult.success("evidence-diff", result);
            System.out.println(resultView.render(commandResult, format));
            if (!commandResult.complete()) {
                throw new CliFailure(ExitCode.STALE);
            }
        } catch (CliFailure failure) {
            throw failure;
        } catch (Exception e) {
            System.out.println(resultView.render(
                    CommandResult.failure("evidence-diff", "EVIDENCE_DIFF_FAILED", e.getMessage()),
                    format
            ));
            throw CliFailure.from(e);
        }
    }
}
