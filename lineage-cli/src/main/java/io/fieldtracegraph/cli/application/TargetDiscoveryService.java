package io.fieldtracegraph.cli.application;

import io.fieldtracegraph.cli.project.ProjectRootResolver;
import io.fieldtracegraph.cli.project.ProjectIndexPaths;
import io.fieldtracegraph.core.model.TargetCandidate;
import io.fieldtracegraph.core.model.TargetStatistics;
import io.fieldtracegraph.core.model.TargetsResult;
import io.fieldtracegraph.core.model.SourceScanOptions;
import io.fieldtracegraph.core.port.IndexRepository;
import io.fieldtracegraph.core.port.IndexRepositoryFactory;
import io.fieldtracegraph.core.query.ReachableScopeCollector;
import io.fieldtracegraph.core.query.TargetCandidateResolver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// 作用：发现方法内可追踪的目标。
// 使用场景：targets 命令列出字段与表达式候选。

public class TargetDiscoveryService {
    private final ProjectRootResolver projectRootResolver;

    private final IndexReadinessService indexReadinessService;

    private final IndexRepositoryFactory repositoryFactory;

    private final ReachableScopeCollector scopeCollector;

    private final TargetCandidateResolver candidateResolver = new TargetCandidateResolver();

    // 作用：注入目标发现依赖；逻辑：保存项目解析、索引就绪、仓库工厂与可达作用域收集器。
    public TargetDiscoveryService(
            ProjectRootResolver projectRootResolver,
            IndexReadinessService indexReadinessService,
            IndexRepositoryFactory repositoryFactory,
            ReachableScopeCollector scopeCollector
    ) {
        this.projectRootResolver = projectRootResolver;
        this.indexReadinessService = indexReadinessService;
        this.repositoryFactory = repositoryFactory;
        this.scopeCollector = scopeCollector;
    }

    // 作用：发现方法内目标；逻辑：校验分页并确保索引就绪，收集可达作用域后解析候选并分页返回。
    public TargetsResult discover(String explicitProject, String methodSelector, boolean noSync, int offset, int limit, SourceScanOptions options) {
        validatePagination(offset, limit);
        Path root = projectRootResolver.resolve(explicitProject);
        indexReadinessService.ensureReady(explicitProject, noSync, false, options);
        try (IndexRepository repository = repositoryFactory.createReadOnly(ProjectIndexPaths.databasePath(root))) {
            var method = repository.findMethodBySelector(methodSelector);
            if (method == null) {
                throw new IllegalArgumentException("METHOD_NOT_FOUND");
            }
            String resolvedMethodId = method.methodId();
            Set<String> scopeIds = scopeCollector.collect(
                    resolvedMethodId,
                    false,
                    scopeId -> repository.callEdgesForScopeIds(List.of(scopeId)),
                    repository.callersByCallee()
            );
            List<TargetCandidate> candidates = candidateResolver.resolve(
                    resolvedMethodId,
                    repository.valueNodesForScopeIds(new ArrayList<>(scopeIds)),
                    repository.dataEdgesForScopeIds(new ArrayList<>(scopeIds))
            );
            int total = candidates.size();
            List<TargetCandidate> targets = candidates.stream()
                    .skip(offset)
                    .limit(limit)
                    .toList();
            return new TargetsResult(
                    resolvedMethodId,
                    method.methodQname(),
                    total,
                    offset,
                    limit,
                    TargetStatistics.from(candidates),
                    targets
            );
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
