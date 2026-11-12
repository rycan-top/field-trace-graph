package io.fieldtracegraph.cli.application;

import io.fieldtracegraph.core.model.IndexStatusResult;
import io.fieldtracegraph.core.model.SourceScanOptions;

// 作用：编排项目初始化流程。
// 使用场景：首次执行 init 时创建索引目录并完成第一次索引。

/**
 * Initializes a project by building and publishing its complete local graph.
 * It never uploads source code or performs a partial/lazy first index.
 */
public class ProjectInitializationService {
    private final ProjectIndexService projectIndexService;

    // 作用：注入索引服务；逻辑：保存用于执行首次全量同步的服务实例。
    public ProjectInitializationService(ProjectIndexService projectIndexService) {
        this.projectIndexService = projectIndexService;
    }

    // 作用：初始化项目索引；逻辑：以强制全量模式调用同步流程。
    public IndexStatusResult initialize(
            String explicitProject,
            long lockTimeoutMillis,
            long indexTimeoutMillis,
            SourceScanOptions options
    ) {
        return projectIndexService.synchronize(
                explicitProject,
                true,
                lockTimeoutMillis,
                indexTimeoutMillis,
                options
        );
    }
}
