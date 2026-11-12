package io.fieldtracegraph.cli.application;

import io.fieldtracegraph.cli.project.ProjectIndexPaths;
import io.fieldtracegraph.cli.project.ProjectRootResolver;
import io.fieldtracegraph.core.model.DoctorResult;
import io.fieldtracegraph.storage.sqlite.SqliteDataSourceFactory;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;

// 作用：检查运行环境是否满足要求。
// 使用场景：doctor 命令验证 JDK、权限与配置。

/**
 * Reports the local runtime capabilities needed by the CLI without repairing
 * anything or scanning personal accounts.
 */
public class RuntimeDoctorService {
    private final ProjectRootResolver projectRootResolver;

    // 作用：注入项目根解析器；逻辑：保存解析器供环境检查定位项目目录。
    public RuntimeDoctorService(ProjectRootResolver projectRootResolver) {
        this.projectRootResolver = projectRootResolver;
    }

    // 作用：检查运行环境；逻辑：解析项目根，采集 JDK、编译器、SQLite 与索引文件状态。
    public DoctorResult check(String explicitProject) {
        Path root = projectRootResolver.resolve(explicitProject);
        Path databasePath = ProjectIndexPaths.databasePath(root);
        boolean sqliteAvailable = SqliteDataSourceFactory.isDriverAvailable();
        return new DoctorResult(
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                System.getProperty("java.home"),
                ToolProvider.getSystemJavaCompiler() != null,
                sqliteAvailable,
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                System.getProperty("user.dir"),
                Files.exists(databasePath)
        );
    }
}
