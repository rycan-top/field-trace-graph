package io.fieldtracegraph.storage.sqlite;

import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.port.IndexAttemptRecorder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：记录每次索引尝试的状态。
// 使用场景：索引失败、超时或被中断时留下可追踪记录。

public final class SqliteIndexAttemptRecorder implements IndexAttemptRecorder {
    private final Connection connection;

    // 作用：绑定数据库连接；逻辑：保存调用方连接供尝试记录写入使用。
    public SqliteIndexAttemptRecorder(Connection connection) {
        this.connection = connection;
    }

    // 作用：登记一次索引尝试；逻辑：更新运行状态、清空旧文件明细并写入本次文件与删除项。
    @Override
    public void begin(
            String attemptId,
            int baseGeneration,
            List<SourceFileRecord> files,
            Map<String, String> previousHashes,
            Set<String> deletedPaths
    ) {
        try {
            updateRunningState(connection, attemptId, baseGeneration);
            clearAttemptFiles(connection);
            insertAttemptFiles(connection, attemptId, files, previousHashes);
            insertDeletedFiles(connection, attemptId, deletedPaths);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to record index attempt", e);
        }
    }

    // 作用：标记尝试失败；逻辑：更新文件明细与索引状态中的错误码、错误信息与结束时间。
    @Override
    public void markFailed(String attemptId, String errorCode, String errorMessage) {
        try {
            String fileSql = "UPDATE index_file_state SET status = 'FAILED', error_code = ?, error_message = ?, finished_at = ? WHERE attempt_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(fileSql)) {
                statement.setString(1, errorCode);
                statement.setString(2, errorMessage);
                statement.setString(3, Instant.now().toString());
                statement.setString(4, attemptId);
                statement.executeUpdate();
            }
            String sql = """
                    UPDATE full_index_state
                    SET last_attempt_status = 'FAILED',
                        last_attempt_finished_at = ?,
                        last_error_code = ?,
                        last_error_message = ?
                    WHERE singleton_id = 1
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, Instant.now().toString());
                statement.setString(2, errorCode);
                statement.setString(3, errorMessage);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to record failed index attempt", e);
        }
    }

    // 作用：写入运行中状态；逻辑：更新尝试 ID、基线代数、开始时间并清空上次错误。
    private void updateRunningState(Connection connection, String attemptId, int baseGeneration) throws SQLException {
        String sql = """
                UPDATE full_index_state
                SET last_attempt_id = ?,
                    last_attempt_base_generation = ?,
                    last_attempt_status = 'RUNNING',
                    last_attempt_started_at = ?,
                    last_attempt_finished_at = NULL,
                    last_error_code = NULL,
                    last_error_message = NULL
                WHERE singleton_id = 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, attemptId);
            statement.setInt(2, baseGeneration);
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    // 作用：清空旧文件明细；逻辑：删除尝试文件表中的全部历史行。
    private void clearAttemptFiles(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM index_file_state");
        }
    }

    // 作用：写入本次文件明细；逻辑：按前次哈希判断新增、修改或复用后批量插入。
    private void insertAttemptFiles(
            Connection connection,
            String attemptId,
            List<SourceFileRecord> files,
            Map<String, String> previousHashes
    ) throws SQLException {
        String sql = """
                INSERT INTO index_file_state(attempt_id, relative_path, change_kind, attempt_hash, status, started_at)
                VALUES (?, ?, ?, ?, 'ANALYZING', ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (SourceFileRecord file : files) {
                String previousHash = previousHashes.get(file.relativePath());
                String changeKind = previousHash == null ? "ADDED" : previousHash.equals(file.contentHash()) ? "REUSED" : "MODIFIED";
                statement.setString(1, attemptId);
                statement.setString(2, file.relativePath());
                statement.setString(3, changeKind);
                statement.setString(4, file.contentHash());
                statement.setString(5, Instant.now().toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    // 作用：写入删除文件明细；逻辑：删除集合为空时跳过，否则批量插入已删除行。
    private void insertDeletedFiles(Connection connection, String attemptId, Set<String> deletedPaths) throws SQLException {
        if (deletedPaths == null || deletedPaths.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO index_file_state(attempt_id, relative_path, change_kind, attempt_hash, status, started_at, finished_at)
                VALUES (?, ?, 'DELETED', NULL, 'DELETED', ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String path : deletedPaths) {
                statement.setString(1, attemptId);
                statement.setString(2, path);
                statement.setString(3, Instant.now().toString());
                statement.setString(4, Instant.now().toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
