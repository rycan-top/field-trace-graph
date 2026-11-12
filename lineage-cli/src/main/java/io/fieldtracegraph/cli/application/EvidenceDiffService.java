package io.fieldtracegraph.cli.application;

import io.fieldtracegraph.cli.dto.EvidenceImportItem;
import io.fieldtracegraph.cli.project.ProjectIndexPaths;
import io.fieldtracegraph.cli.project.ProjectRootResolver;
import io.fieldtracegraph.core.model.EvidenceDiffItem;
import io.fieldtracegraph.core.model.EvidenceDiffResult;
import io.fieldtracegraph.core.port.IndexRepository;
import io.fieldtracegraph.core.port.IndexRepositoryFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// 作用：计算两次证据集合之间的差异。
// 使用场景：evidence diff 命令比较版本或环境变化。

/**
 * Compares runtime evidence identities with the currently published static
 * graph. A missing key means capture and graph disagree; it does not silently
 * create a new edge.
 */
public class EvidenceDiffService {
    private final ProjectRootResolver projectRootResolver;
    private final IndexRepositoryFactory repositoryFactory;

    // 作用：注入差异比较依赖；逻辑：保存项目解析器与只读仓库工厂。
    public EvidenceDiffService(
            ProjectRootResolver projectRootResolver,
            IndexRepositoryFactory repositoryFactory
    ) {
        this.projectRootResolver = projectRootResolver;
        this.repositoryFactory = repositoryFactory;
    }

    // 作用：比较证据与静态图；逻辑：逐条校验逻辑边是否存在并汇总匹配率。
    public EvidenceDiffResult diff(String explicitProject, List<EvidenceImportItem> items) {
        Path root = projectRootResolver.resolve(explicitProject);
        Path databasePath = ProjectIndexPaths.databasePath(root);
        if (!Files.exists(databasePath)) {
            throw new IllegalStateException("INDEX_NOT_FOUND");
        }
        List<EvidenceDiffItem> comparisons = new ArrayList<>();
        int matched = 0;
        try (IndexRepository repository = repositoryFactory.createReadOnly(databasePath)) {
            if (!repository.isSchemaInitialized()) {
                throw new IllegalStateException("INDEX_NOT_FOUND");
            }
            for (EvidenceImportItem item : items) {
                if (item.logicalEdgeKey() == null || item.logicalEdgeKey().isBlank()) {
                    throw new IllegalArgumentException("EVIDENCE_LOGICAL_EDGE_KEY_REQUIRED");
                }
                boolean present = repository.hasLogicalEdge(item.logicalEdgeKey());
                if (present) {
                    matched++;
                }
                comparisons.add(new EvidenceDiffItem(
                        item.logicalEdgeKey(),
                        item.evidenceKind(),
                        item.adapter(),
                        item.coverage(),
                        item.observedAt(),
                        present
                ));
            }
        }
        int observed = comparisons.size();
        int missing = observed - matched;
        return new EvidenceDiffResult(
                observed,
                matched,
                missing,
                observed == 0 ? 1d : (double) matched / observed,
                comparisons
        );
    }
}
