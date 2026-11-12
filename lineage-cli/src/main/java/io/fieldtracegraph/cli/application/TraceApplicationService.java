package io.fieldtracegraph.cli.application;

import io.fieldtracegraph.cli.project.ProjectRootResolver;
import io.fieldtracegraph.core.model.SourceScanOptions;
import io.fieldtracegraph.core.model.TraceResult;
import io.fieldtracegraph.core.query.CallScopeMode;

import java.nio.file.Path;

// 作用：编排一次追踪的同步、快照读取、质量门禁与退出码。
// 使用场景：trace 命令在 --no-sync、--allow-stale 与门禁参数下运行。

/**
 * Application use case for the trace command. It resolves the project, makes
 * the index ready, then delegates the read-only traversal to the query
 * service. It never writes SQL or runs a traversal itself.
 */
public class TraceApplicationService {
    private final ProjectRootResolver projectRootResolver;
    private final IndexReadinessService indexReadinessService;
    private final LineageQueryService lineageQueryService;

    // 作用：注入追踪依赖；逻辑：保存项目解析、索引进备与查询服务。
    public TraceApplicationService(
            ProjectRootResolver projectRootResolver,
            IndexReadinessService indexReadinessService,
            LineageQueryService lineageQueryService
    ) {
        this.projectRootResolver = projectRootResolver;
        this.indexReadinessService = indexReadinessService;
        this.lineageQueryService = lineageQueryService;
    }

    // 作用：执行追踪；逻辑：解析项目根、确保索引就绪后委托查询服务做只读遍历。
    public TraceResult execute(
            String explicitProject,
            String methodSelector,
            String targetExpression,
            String direction,
            boolean noSync,
            boolean allowStale,
            int maxDepth,
            int maxEdges,
            long queryTimeoutMillis,
            SourceScanOptions options,
            CallScopeMode callScope,
            boolean includeDependencies,
            boolean strictContracts
    ) {
        Path root = projectRootResolver.resolve(explicitProject);
        indexReadinessService.ensureReady(explicitProject, noSync, allowStale, options);
        return lineageQueryService.trace(
                root,
                methodSelector,
                targetExpression,
                direction,
                maxDepth,
                maxEdges,
                queryTimeoutMillis,
                options,
                callScope,
                includeDependencies,
                strictContracts
        );
    }
}
