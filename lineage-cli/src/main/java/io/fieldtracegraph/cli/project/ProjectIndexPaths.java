package io.fieldtracegraph.cli.project;

import java.nio.file.Path;

// 作用：定义项目索引目录下的标准路径。
// 使用场景：定位数据库、写锁与契约文件。

/**
 * Canonical locations for a project's local index. Keeping these in one place
 * prevents each service from inventing a slightly different path.
 */
public final class ProjectIndexPaths {
    // 作用：阻止路径工具类被实例化；逻辑：私有空构造器。
    private ProjectIndexPaths() {
    }

    // 作用：定位工具目录；逻辑：在项目根下拼出隐藏目录名。
    public static Path toolDirectory(Path projectRoot) {
        return projectRoot.resolve(".field-trace-graph");
    }

    // 作用：定位索引数据库；逻辑：在工具目录下拼出数据库文件名。
    public static Path databasePath(Path projectRoot) {
        return toolDirectory(projectRoot).resolve("trace.db");
    }
}
