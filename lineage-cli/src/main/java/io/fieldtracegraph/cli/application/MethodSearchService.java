package io.fieldtracegraph.cli.application;

import io.fieldtracegraph.cli.project.ProjectRootResolver;
import io.fieldtracegraph.cli.project.ProjectIndexPaths;
import io.fieldtracegraph.core.model.MethodCandidate;
import io.fieldtracegraph.core.model.MethodsResult;
import io.fieldtracegraph.core.model.SourceScanOptions;
import io.fieldtracegraph.core.port.IndexRepository;
import io.fieldtracegraph.core.port.IndexRepositoryFactory;

import java.nio.file.Path;
import java.util.List;

// 作用：按名称或签名搜索方法。
// 使用场景：methods 命令。

public class MethodSearchService {
    private final ProjectRootResolver projectRootResolver;

    private final IndexReadinessService indexReadinessService;

    private final IndexRepositoryFactory repositoryFactory;

    // 作用：注入方法搜索依赖；逻辑：保存项目解析、索引就绪检查与只读仓库工厂。
    public MethodSearchService(ProjectRootResolver projectRootResolver, IndexReadinessService indexReadinessService, IndexRepositoryFactory repositoryFactory) {
        this.projectRootResolver = projectRootResolver;
        this.indexReadinessService = indexReadinessService;
        this.repositoryFactory = repositoryFactory;
    }
    // 作用：搜索方法；逻辑：校验分页，确保索引就绪后以只读仓库统计并查询候选。
    public MethodsResult search(
            String explicitProject,
            String name,
            int offset,
            int limit,
            boolean includeAccessors,
            boolean noSync,
            SourceScanOptions options
    ) {
        validatePagination(offset, limit);
        Path root = projectRootResolver.resolve(explicitProject);
        indexReadinessService.ensureReady(explicitProject, noSync, false, options);
        try (IndexRepository repository = repositoryFactory.createReadOnly(ProjectIndexPaths.databasePath(root))) {
            int total = repository.countMethods(name, includeAccessors);
            List<MethodCandidate> candidates = repository.findMethods(name, offset, limit, includeAccessors);
            return new MethodsResult(total, offset, limit, candidates);
        }
    }

    // 作用：校验分页参数；逻辑：偏移非负且上限在 1 到 100 之间。
    private void validatePagination(int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }
}
