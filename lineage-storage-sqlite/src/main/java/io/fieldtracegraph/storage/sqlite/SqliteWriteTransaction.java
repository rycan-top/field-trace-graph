package io.fieldtracegraph.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;

// 作用：封装 SQLite 写事务。
// 使用场景：发布索引与导入证据时保证原子性。

/**
 * Owns the JDBC transaction boundary for a single connection. It keeps
 * commit, rollback and auto-commit reset together so publishers never leave a
 * half-written transaction behind when a DAO insert fails.
 */
public final class SqliteWriteTransaction {
    private final Connection connection;
    private boolean active;

    // 作用：绑定目标连接；逻辑：保存连接引用并把事务状态初始化为未激活。
    public SqliteWriteTransaction(Connection connection) {
        this.connection = connection;
    }

    // 作用：开启写事务；逻辑：关闭自动提交并标记事务已激活。
    public void begin() {
        try {
            connection.setAutoCommit(false);
            active = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to begin SQLite write transaction", e);
        }
    }

    // 作用：提交写事务；逻辑：执行提交并清除激活标记。
    public void commit() {
        try {
            connection.commit();
            active = false;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to commit SQLite write transaction", e);
        }
    }

    // 作用：回滚写事务；逻辑：仅在事务激活时执行回滚并清除标记。
    public void rollback() {
        if (!active) {
            return;
        }
        try {
            connection.rollback();
            active = false;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to roll back SQLite write transaction", e);
        }
    }

    // 作用：恢复自动提交；逻辑：重新开启自动提交并清除事务激活状态。
    public void resetAutoCommit() {
        try {
            connection.setAutoCommit(true);
            active = false;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to reset SQLite connection", e);
        }
    }
}
