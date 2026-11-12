package io.fieldtracegraph.cli.application;

import io.fieldtracegraph.cli.config.LineageRuntimeProperties;
import io.fieldtracegraph.core.model.IndexStatusResult;
import io.fieldtracegraph.core.model.SourceScanOptions;

// 作用：在查询前确保索引存在且足够新鲜。
// 使用场景：methods、targets、trace 自动同步时。

/**
 * Ensures a queryable snapshot exists before a read command runs. It decides
 * between reusing the current generation, refusing stale data, or requesting
 * a synchronisation; it does not itself extract ASTs or render output.
 */
public class IndexReadinessService {
    private final IndexStatusService indexStatusService;
    private final ProjectIndexService projectIndexService;
    private final LineageRuntimeProperties runtimeOptions;

    // 作用：注入就绪检查依赖；逻辑：保存状态服务、索引服务与运行预算配置。
    public IndexReadinessService(
            IndexStatusService indexStatusService,
            ProjectIndexService projectIndexService,
            LineageRuntimeProperties runtimeOptions
    ) {
        this.indexStatusService = indexStatusService;
        this.projectIndexService = projectIndexService;
        this.runtimeOptions = runtimeOptions;
    }

    // 作用：确保查询前索引可用；逻辑：禁同步时校验绑定与新鲜度，否则按需同步并在允许时回退旧索引。
    public IndexStatusResult ensureReady(String explicitProject, boolean noSync, boolean allowStale, SourceScanOptions options) {
        if (noSync) {
            indexStatusService.verifyBinding(explicitProject);
            IndexStatusResult current = indexStatusService.inspect(explicitProject, options);
            if (!allowStale && (!"FRESH".equals(current.freshness()) || !"READY".equals(current.indexStatus()))) {
                throw new IllegalStateException("INDEX_STALE");
            }
            return current;
        }
        IndexStatusResult current = indexStatusService.inspect(explicitProject, options);
        if ("FRESH".equals(current.freshness()) && "READY".equals(current.indexStatus())) {
            return current;
        }
        try {
            return projectIndexService.synchronize(
                    explicitProject,
                    false,
                    runtimeOptions.lockTimeoutMillis(),
                    runtimeOptions.indexTimeoutMillis(),
                    options
            );
        } catch (RuntimeException syncFailure) {
            if (allowStale && "READY".equals(current.indexStatus())) {
                return current;
            }
            throw syncFailure;
        }
    }
}
