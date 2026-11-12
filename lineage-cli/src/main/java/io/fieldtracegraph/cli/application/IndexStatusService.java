package io.fieldtracegraph.cli.application;

import io.fieldtracegraph.cli.config.LineageRuntimeProperties;
import io.fieldtracegraph.cli.project.ProjectIndexPaths;
import io.fieldtracegraph.cli.project.ProjectRootResolver;
import io.fieldtracegraph.core.model.IndexStatusResult;
import io.fieldtracegraph.core.model.IndexQuality;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.SourceScanOptions;
import io.fieldtracegraph.core.model.SymbolStatistics;
import io.fieldtracegraph.core.port.IndexRepository;
import io.fieldtracegraph.core.port.IndexRepositoryFactory;
import io.fieldtracegraph.core.port.ProjectSourceReader;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.semantic.context.SemanticContextFingerprint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 作用：组装索引状态与质量指标。
// 使用场景：status 命令输出与质量门禁判断。

/**
 * Read-only inspection of the published index and its freshness against the
 * current source tree. It never synchronises or repairs the index.
 */
public class IndexStatusService {
    private final ProjectRootResolver projectRootResolver;
    private final ProjectSourceReader sourceReader;
    private final IndexRepositoryFactory repositoryFactory;
    private final SemanticContextFingerprint contextFingerprint = new SemanticContextFingerprint();
    private final LineageRuntimeProperties runtimeOptions;

    // 作用：注入状态检查依赖；逻辑：保存项目解析、源文件读取、仓库工厂与运行配置。
    public IndexStatusService(
            ProjectRootResolver projectRootResolver,
            ProjectSourceReader sourceReader,
            IndexRepositoryFactory repositoryFactory,
            LineageRuntimeProperties runtimeOptions
    ) {
        this.projectRootResolver = projectRootResolver;
        this.sourceReader = sourceReader;
        this.repositoryFactory = repositoryFactory;
        this.runtimeOptions = runtimeOptions;
    }

    // 作用：检查索引状态；逻辑：以不包含质量统计的方式调用完整重载。
    public IndexStatusResult inspect(String explicitProject, SourceScanOptions options) {
        return inspect(explicitProject, options, false);
    }

    // 作用：检查索引状态；逻辑：比对当前与索引内文件哈希及上下文指纹，判定新鲜度并可附带质量指标。
    public IndexStatusResult inspect(
            String explicitProject,
            SourceScanOptions options,
            boolean includeQuality
    ) {
        Path root = projectRootResolver.resolve(explicitProject);
        Path databasePath = ProjectIndexPaths.databasePath(root);
        if (!Files.exists(databasePath)) {
            IndexStatusResult empty = new IndexStatusResult(0, "EMPTY", "UNKNOWN",
                    runtimeOptions.parserVersion(), null, null, "IDLE", null, null,
                    0, 0, SymbolStatistics.empty(), 0, 0, 0, 0);
            return includeQuality ? empty.withQuality(IndexQuality.empty()) : empty;
        }
        try (IndexRepository repository = repositoryFactory.createReadOnly(databasePath)) {
            if (!repository.isSchemaInitialized()) {
                IndexStatusResult empty = new IndexStatusResult(0, "EMPTY", "UNKNOWN",
                        runtimeOptions.parserVersion(), null, null, "IDLE", null, null,
                        0, 0, SymbolStatistics.empty(), 0, 0, 0, 0);
                return includeQuality ? empty.withQuality(IndexQuality.empty()) : empty;
            }
            List<SourceFileRecord> files = sourceReader.capture(root, options);
            Map<String, String> currentHashes = files.stream()
                    .collect(Collectors.toMap(SourceFileRecord::relativePath, SourceFileRecord::contentHash));
            Map<String, String> indexedHashes = repository.sourceFileHashes();
            boolean contextFresh = contextFingerprint.compute(
                    files,
                    runtimeOptions.parserVersion(),
                    root,
                    options.includeTests(),
                    options.classpathEntries()
            ).equals(repository.contextHash());
            String freshness = currentHashes.equals(indexedHashes) && contextFresh ? "FRESH" : "STALE";
            IndexStatusResult status = repository.toStatusResult(freshness);
            return includeQuality ? status.withQuality(repository.qualityStatistics()) : status;
        }
    }

    // 作用：校验项目绑定；逻辑：比较索引记录的项目路径指纹，不一致时拒绝复用索引。
    public void verifyBinding(String explicitProject) {
        Path root = projectRootResolver.resolve(explicitProject);
        Path databasePath = ProjectIndexPaths.databasePath(root);
        if (!Files.exists(databasePath)) {
            return;
        }
        try (IndexRepository repository = repositoryFactory.createReadOnly(databasePath)) {
            if (!repository.isSchemaInitialized()) {
                return;
            }
            String stored = repository.projectRootFingerprint();
            if (stored != null && !stored.isBlank() && !stored.equals(Ids.sha256(root.toString()))) {
                throw new IllegalStateException("PROJECT_BINDING_CHANGED");
            }
        }
    }
}
