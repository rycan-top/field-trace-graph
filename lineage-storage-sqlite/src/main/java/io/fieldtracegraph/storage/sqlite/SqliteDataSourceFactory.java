package io.fieldtracegraph.storage.sqlite;


import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

// 作用：创建并配置 SQLite 连接。
// 使用场景：设置 WAL、busy timeout、外键与只读模式。

public class SqliteDataSourceFactory {
    // 作用：检测 SQLite 驱动是否可用；逻辑：尝试解析 JDBC 驱动，异常时返回不可用。
    public static boolean isDriverAvailable() {
        try {
            return DriverManager.getDriver("jdbc:sqlite:") != null;
        } catch (SQLException e) {
            return false;
        }
    }

    // 作用：打开可读写连接；逻辑：复用带只读开关的重载并传入 false。
    public Connection open(Path databasePath) {
        return open(databasePath, false);
    }

    // 作用：按读写模式打开连接；逻辑：拼接只读或普通 URL，设置外键、超时与 WAL 后返回连接。
    public Connection open(Path databasePath, boolean readOnly) {
        try {
            String url = readOnly
                    ? "jdbc:sqlite:file:" + databasePath + "?mode=ro"
                    : "jdbc:sqlite:" + databasePath;
            Connection connection = DriverManager.getConnection(url);
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 5000");
                if (!readOnly) {
                    statement.execute("PRAGMA journal_mode = WAL");
                    try (var journalResult = statement.executeQuery("PRAGMA journal_mode")) {
                        if (journalResult.next() && !"wal".equalsIgnoreCase(journalResult.getString(1))) {
                            throw new SQLException("SQLite WAL mode is not available on the project filesystem");
                        }
                    }
                    /*
                     * The index is a rebuildable artifact, so NORMAL synchronous
                     * durability is enough: it survives process crashes and only
                     * risks the last transaction on an OS-level crash. A 64 MiB
                     * page cache matters for large repositories, where the default
                     * 2 MiB cache forces constant page churn during the bulk
                     * insert of hundreds of thousands of nodes and edges.
                     */
                    statement.execute("PRAGMA synchronous = NORMAL");
                    statement.execute("PRAGMA cache_size = -65536");
                    statement.execute("PRAGMA temp_store = MEMORY");
                    statement.execute("PRAGMA wal_autocheckpoint = 2000");
                }
            }
            return connection;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to open SQLite database", e);
        }
    }
}
