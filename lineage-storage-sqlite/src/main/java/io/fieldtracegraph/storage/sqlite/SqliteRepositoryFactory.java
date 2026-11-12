package io.fieldtracegraph.storage.sqlite;

import io.fieldtracegraph.core.port.IndexRepository;
import io.fieldtracegraph.core.port.IndexRepositoryFactory;

import java.nio.file.Path;

// 作用：按数据库路径创建 SQLite 仓库。
// 使用场景：索引与查询服务获取仓库实例。

public class SqliteRepositoryFactory implements IndexRepositoryFactory {
    private final SqliteDataSourceFactory connectionFactory;

    // 作用：注入连接工厂；逻辑：保存创建仓库时复用的数据源工厂。
    public SqliteRepositoryFactory(SqliteDataSourceFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }
    // 作用：创建可读写仓库；逻辑：以非只读模式构造 SQLite 索引仓库。
    @Override
    public IndexRepository create(Path databasePath) {
        return new SqliteRepository(connectionFactory, databasePath, false);
    }

    // 作用：创建只读仓库；逻辑：以只读模式构造 SQLite 索引仓库。
    @Override
    public IndexRepository createReadOnly(Path databasePath) {
        return new SqliteRepository(connectionFactory, databasePath, true);
    }
}
