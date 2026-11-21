package io.fieldtracegraph.cli.controller;

import io.fieldtracegraph.cli.application.ClasspathResolutionService;
import io.fieldtracegraph.cli.error.CliFailure;
import io.fieldtracegraph.cli.project.ProjectRootResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;

// 作用：生成依赖类路径文件。
// 使用场景：索引前用 Maven 依赖补齐外部类型，减少无法桥接的外部调用。

@Command(name = "classpath", description = "Resolve dependency jars from Maven modules")
public class ClasspathCommand implements Runnable {
    private final ClasspathResolutionService service;
    private final ProjectRootResolver projectRootResolver;

    // 作用：注入解析服务；逻辑：保存服务与根目录解析器。
    public ClasspathCommand(ClasspathResolutionService service, ProjectRootResolver projectRootResolver) {
        this.service = service;
        this.projectRootResolver = projectRootResolver;
    }

    @Option(names = "--project", description = "Project root; defaults to the current directory")
    private String project;

    @Option(names = "--output", description = "Output file; defaults to <project>/.field-trace-graph/classpath.txt")
    private String output;

    @Option(names = "--max-modules", defaultValue = "12", description = "Maximum Maven modules to resolve")
    private int maxModules;

    @Option(names = "--module-timeout", defaultValue = "180", description = "Per-module timeout in seconds")
    private int moduleTimeout;

    @Option(names = "--offline-only", defaultValue = "false", description = "Only use the local Maven repository")
    private boolean offlineOnly;

    // 作用：执行解析；逻辑：计算默认输出路径后调用服务并打印摘要。
    @Override
    public void run() {
        Path root = projectRootResolver.resolve(project);
        Path out = output == null || output.isBlank()
                ? root.resolve(".field-trace-graph").resolve("classpath.txt")
                : Path.of(output);
        try {
            ClasspathResolutionService.Summary summary =
                    service.resolve(project, out, maxModules, moduleTimeout, offlineOnly);
            System.out.println("Classpath: entries=" + summary.entries()
                    + " modules=" + summary.modules()
                    + " failures=" + summary.failures().size()
                    + " localRepository=" + summary.localRepositoryEntries()
                    + " file=" + summary.output());
            if (!summary.failures().isEmpty()) {
                System.out.println("Failed modules: " + String.join(", ", summary.failures()));
            }
            System.out.println("Next: ftg index --project " + summary.root()
                    + " --classpath-file " + summary.output());
        } catch (Exception e) {
            throw CliFailure.from(e);
        }
    }
}
