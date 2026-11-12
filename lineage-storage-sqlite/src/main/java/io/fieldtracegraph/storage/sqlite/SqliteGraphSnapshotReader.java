package io.fieldtracegraph.storage.sqlite;

import io.fieldtracegraph.core.port.GraphReadSession;
import io.fieldtracegraph.core.port.GraphSnapshotReader;

import java.nio.file.Path;

// 作用：打开只读的图快照。
// 使用场景：trace 读取已发布的 generation，保证视图一致。

/**
 * Opens a read-only SQLite snapshot session. Connection creation and the
 * read-only PRAGMA stay here; graph SQL lives in the DAO layer.
 */
public final class SqliteGraphSnapshotReader implements GraphSnapshotReader {
    private final SqliteDataSourceFactory dataSourceFactory;

    // 作用：注入数据源工厂；逻辑：保存用于按路径创建只读连接的工厂引用。
    public SqliteGraphSnapshotReader(SqliteDataSourceFactory dataSourceFactory) {
        this.dataSourceFactory = dataSourceFactory;
    }

    // 作用：打开只读图快照；逻辑：以只读模式建连并包装为查询会话。
    @Override
    public GraphReadSession open(Path databasePath) {
        return new SqliteGraphReadSession(dataSourceFactory.open(databasePath, true));
    }
}
