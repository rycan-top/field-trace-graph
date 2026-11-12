package io.fieldtracegraph.cli.config;

import io.fieldtracegraph.cli.application.IndexReadinessService;
import io.fieldtracegraph.cli.application.IndexStatusService;
import io.fieldtracegraph.cli.application.EvidenceImportService;
import io.fieldtracegraph.cli.application.EvidenceDiffService;
import io.fieldtracegraph.cli.application.LineageQueryService;
import io.fieldtracegraph.cli.application.MethodSearchService;
import io.fieldtracegraph.cli.application.ProjectIndexService;
import io.fieldtracegraph.cli.application.ProjectInitializationService;
import io.fieldtracegraph.cli.application.RuntimeDoctorService;
import io.fieldtracegraph.cli.application.SourceEvidenceService;
import io.fieldtracegraph.cli.application.TargetDiscoveryService;
import io.fieldtracegraph.cli.application.TraceApplicationService;
import io.fieldtracegraph.cli.controller.DoctorCommand;
import io.fieldtracegraph.cli.controller.EvidenceCommand;
import io.fieldtracegraph.cli.controller.EvidenceDiffCommand;
import io.fieldtracegraph.cli.controller.EvidenceImportCommand;
import io.fieldtracegraph.cli.controller.IndexCommand;
import io.fieldtracegraph.cli.controller.InitCommand;
import io.fieldtracegraph.cli.controller.MethodsCommand;
import io.fieldtracegraph.cli.controller.RootCommand;
import io.fieldtracegraph.cli.controller.SourceCommand;
import io.fieldtracegraph.cli.controller.StatusCommand;
import io.fieldtracegraph.cli.controller.TargetsCommand;
import io.fieldtracegraph.cli.controller.TraceCommand;
import io.fieldtracegraph.cli.error.CliExecutionExceptionHandler;
import io.fieldtracegraph.cli.project.LocalProjectSourceReader;
import io.fieldtracegraph.cli.project.ProjectRootResolver;
import io.fieldtracegraph.cli.project.SourcePathPolicy;
import io.fieldtracegraph.cli.project.SourceFileScanner;
import io.fieldtracegraph.cli.view.ResultView;
import io.fieldtracegraph.javaanalysis.JavaSourceAnalyzer;
import io.fieldtracegraph.javaanalysis.project.JavaLanguageLevelResolver;
import io.fieldtracegraph.storage.sqlite.SqliteDataSourceFactory;
import io.fieldtracegraph.storage.sqlite.SqliteGraphSnapshotReader;
import io.fieldtracegraph.storage.sqlite.SqliteRepositoryFactory;
import io.fieldtracegraph.core.query.ReachableScopeCollector;
import picocli.CommandLine;

// 作用：手工装配整条对象链并注册命令树。
// 使用场景：程序启动时构建 CLI，无需依赖注入容器。

public final class LineageBeanConfiguration {
    private final RootCommand rootCommand;
    private final InitCommand initCommand;
    private final IndexCommand indexCommand;
    private final StatusCommand statusCommand;
    private final MethodsCommand methodsCommand;
    private final TargetsCommand targetsCommand;
    private final TraceCommand traceCommand;
    private final SourceCommand sourceCommand;
    private final DoctorCommand doctorCommand;
    private final EvidenceCommand evidenceCommand;
    private final EvidenceImportCommand evidenceImportCommand;
    private final EvidenceDiffCommand evidenceDiffCommand;

    // 作用：装配全部依赖并构建命令；逻辑：加载配置，自底向上创建扫描、分析、存储与服务对象，再组装命令树。
    public LineageBeanConfiguration() {
        LineageRuntimeProperties runtimeOptions = new ToolConfigurationLoader().load();
        ResultView resultView = new ResultView();
        ProjectRootResolver projectRootResolver = new ProjectRootResolver();
        SourceFileScanner sourceScanner = new SourceFileScanner(
                SourcePathPolicy.from(runtimeOptions),
                new JavaLanguageLevelResolver()
        );
        LocalProjectSourceReader sourceReader = new LocalProjectSourceReader(sourceScanner);
        JavaSourceAnalyzer sourceAnalyzer = new JavaSourceAnalyzer();
        SqliteDataSourceFactory connectionFactory = new SqliteDataSourceFactory();
        SqliteRepositoryFactory repositoryFactory = new SqliteRepositoryFactory(connectionFactory);
        SqliteGraphSnapshotReader graphSnapshotReader = new SqliteGraphSnapshotReader(connectionFactory);

        IndexStatusService indexStatusService = new IndexStatusService(
                projectRootResolver,
                sourceReader,
                repositoryFactory,
                runtimeOptions
        );
        ProjectIndexService projectIndexService = new ProjectIndexService(
                projectRootResolver,
                sourceReader,
                sourceAnalyzer,
                repositoryFactory,
                runtimeOptions
        );
        ProjectInitializationService projectInitializationService = new ProjectInitializationService(
                projectIndexService
        );
        IndexReadinessService indexReadinessService = new IndexReadinessService(
                indexStatusService,
                projectIndexService,
                runtimeOptions
        );
        RuntimeDoctorService runtimeDoctorService = new RuntimeDoctorService(projectRootResolver);
        EvidenceImportService evidenceImportService = new EvidenceImportService(
                projectRootResolver,
                repositoryFactory
        );
        EvidenceDiffService evidenceDiffService = new EvidenceDiffService(
                projectRootResolver,
                repositoryFactory
        );
        MethodSearchService methodSearchService = new MethodSearchService(
                projectRootResolver,
                indexReadinessService,
                repositoryFactory
        );
        TargetDiscoveryService targetDiscoveryService = new TargetDiscoveryService(
                projectRootResolver,
                indexReadinessService,
                repositoryFactory,
                new ReachableScopeCollector(runtimeOptions.maxCalleeDepth())
        );
        LineageQueryService lineageQueryService = new LineageQueryService(
                graphSnapshotReader,
                sourceReader,
                runtimeOptions
        );
        TraceApplicationService traceApplicationService = new TraceApplicationService(
                projectRootResolver,
                indexReadinessService,
                lineageQueryService
        );
        SourceEvidenceService sourceEvidenceService = new SourceEvidenceService(
                projectRootResolver,
                sourceReader,
                repositoryFactory
        );

        rootCommand = new RootCommand(resultView);
        initCommand = new InitCommand(projectInitializationService, resultView);
        indexCommand = new IndexCommand(projectIndexService, resultView);
        statusCommand = new StatusCommand(indexStatusService, resultView);
        methodsCommand = new MethodsCommand(methodSearchService, resultView);
        targetsCommand = new TargetsCommand(targetDiscoveryService, resultView);
        traceCommand = new TraceCommand(traceApplicationService, resultView);
        sourceCommand = new SourceCommand(sourceEvidenceService, resultView);
        doctorCommand = new DoctorCommand(runtimeDoctorService, resultView);
        evidenceCommand = new EvidenceCommand();
        evidenceImportCommand = new EvidenceImportCommand(evidenceImportService, resultView);
        evidenceDiffCommand = new EvidenceDiffCommand(evidenceDiffService, resultView);
    }

    // 作用：创建 picocli 命令树；逻辑：挂载根命令、异常处理器与各子命令，并注册证据子命令组。
    public CommandLine createCommandLine() {
        CommandLine commandLine = new CommandLine(rootCommand)
                .setExecutionExceptionHandler(new CliExecutionExceptionHandler())
                .addSubcommand(initCommand)
                .addSubcommand(indexCommand)
                .addSubcommand(statusCommand)
                .addSubcommand(methodsCommand)
                .addSubcommand(targetsCommand)
                .addSubcommand(traceCommand)
                .addSubcommand(sourceCommand)
                .addSubcommand(doctorCommand)
                .addSubcommand(evidenceCommand);
        commandLine.getSubcommands().get("evidence").addSubcommand(evidenceImportCommand);
        commandLine.getSubcommands().get("evidence").addSubcommand(evidenceDiffCommand);
        return commandLine;
    }
}
