package io.fieldtracegraph.core.port;

import java.nio.file.Path;

// 作用：索引仓库工厂端口。
// 使用场景：按项目数据库路径创建具体仓库实现。

public interface IndexRepositoryFactory {
    // 作用：创建可读写仓库；逻辑：按数据库路径打开连接并返回索引仓库实例。
    IndexRepository create(Path databasePath);

    // 作用：创建只读仓库；逻辑：以只读模式打开连接，供查询与状态读取使用。
    IndexRepository createReadOnly(Path databasePath);
}
