package io.fieldtracegraph.cli.controller;

import io.fieldtracegraph.cli.application.EvidenceFileReader;
import io.fieldtracegraph.cli.application.EvidenceImportService;
import io.fieldtracegraph.cli.dto.EvidenceImportItem;
import io.fieldtracegraph.cli.error.CliFailure;
import io.fieldtracegraph.cli.view.ResultView;
import io.fieldtracegraph.core.model.CommandResult;
import io.fieldtracegraph.core.model.EvidenceImportResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;

// 作用：运行时证据导入命令。
// 使用场景：ftg evidence import 导入追踪、SQL 或序列化证据。

@Command(
        name = "import",
        description = "Append runtime observations to existing logical edges",
        mixinStandardHelpOptions = true
)
public class EvidenceImportCommand implements Runnable {
    private final EvidenceImportService evidenceImportService;
    private final ResultView resultView;
    private final EvidenceFileReader evidenceFileReader = new EvidenceFileReader();

    // 作用：注入证据导入依赖；逻辑：保存导入服务与结果渲染器。
    public EvidenceImportCommand(
            EvidenceImportService evidenceImportService,
            ResultView resultView
    ) {
        this.evidenceImportService = evidenceImportService;
        this.resultView = resultView;
    }

    @Option(names = "--project", description = "Project root; defaults to the current directory")
    private String project;

    @Option(names = "--file", required = true, description = "Runtime evidence JSON file")
    private Path file;

    @Option(names = "--format", defaultValue = "text", description = "Output format: text or json")
    private String format;

    // 作用：执行证据导入；逻辑：解析证据文件后调用导入服务，并按格式输出结果或失败信息。
    @Override
    public void run() {
        try {
            List<EvidenceImportItem> items = evidenceFileReader.read(file);
            EvidenceImportResult result = evidenceImportService.importEvidence(project, items);
            System.out.println(resultView.render(
                    CommandResult.success("evidence-import", result),
                    format
            ));
        } catch (Exception e) {
            System.out.println(resultView.render(
                    CommandResult.failure("evidence-import", "EVIDENCE_IMPORT_FAILED", e.getMessage()),
                    format
            ));
            throw CliFailure.from(e);
        }
    }

}
