package io.fieldtracegraph.storage.sqlite.dao;

import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.storage.sqlite.mapping.GraphRowMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 作用：读写源文件表。
// 使用场景：增量判定、快照校验与文件级证据。

/**
 * SQL for the source_file table. It uses the caller-provided connection and
 * never opens, commits or rolls back a transaction itself.
 */
public final class SourceFileDao {
    private final Connection connection;

    // 作用：绑定数据库连接；逻辑：保存调用方连接，不在内部开启事务。
    public SourceFileDao(Connection connection) {
        this.connection = connection;
    }

    // 作用：查询文件相对路径；逻辑：按文件 ID 取路径，未命中返回 null。
    public String relativePath(String fileId) {
        String query = "SELECT relative_path FROM source_file WHERE file_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, fileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query source file", e);
        }
    }

    // 作用：按 ID 查询源文件元数据；逻辑：映射单行结果为源文件记录，未命中返回 null。
    public SourceFileRecord byId(String fileId) {
        String query = """
                SELECT file_id, relative_path, module_id, content_hash, file_size_bytes, encoding, language_level, analysis_status
                FROM source_file
                WHERE file_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, fileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? GraphRowMapper.sourceFile(resultSet) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query source file by id", e);
        }
    }

    // 作用：批量查询源文件元数据；逻辑：按 ID 列表动态查询并组装为映射。
    public Map<String, SourceFileRecord> byIds(List<String> fileIds) {
        if (fileIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(fileIds.size(), "?"));
        String query = """
                SELECT file_id, relative_path, module_id, content_hash, file_size_bytes, encoding, language_level, analysis_status
                FROM source_file
                WHERE file_id IN (""" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            for (int i = 0; i < fileIds.size(); i++) {
                statement.setString(i + 1, fileIds.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, SourceFileRecord> result = new HashMap<>();
                while (resultSet.next()) {
                    SourceFileRecord record = GraphRowMapper.sourceFile(resultSet);
                    result.put(record.fileId(), record);
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query source files by ids", e);
        }
    }

    // 作用：读取全部文件哈希；逻辑：扫描源文件表并组装路径到哈希的映射。
    public Map<String, String> hashes() {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT relative_path, content_hash FROM source_file")) {
            Map<String, String> result = new HashMap<>();
            while (resultSet.next()) {
                result.put(resultSet.getString(1), resultSet.getString(2));
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read source file hashes", e);
        }
    }

    // 作用：统计源文件数量；逻辑：委托通用计数方法统计源文件表。
    public int count() {
        return countTable("source_file");
    }

    // 作用：批量写入源文件记录；逻辑：逐条绑定字段并批量执行插入。
    public void insert(List<SourceFileRecord> files) {
        String sql = """
                INSERT INTO source_file(file_id, relative_path, module_id, content_hash, file_size_bytes,
                                       encoding, language_level, analysis_status, analysis_issue_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (SourceFileRecord file : files) {
                statement.setString(1, file.fileId());
                statement.setString(2, file.relativePath());
                statement.setString(3, file.moduleId());
                statement.setString(4, file.contentHash());
                statement.setLong(5, file.fileSize());
                statement.setString(6, file.encoding());
                statement.setString(7, file.languageLevel());
                statement.setString(8, file.analysisStatus() == null ? "READY" : file.analysisStatus());
                statement.setString(9, file.analysisIssueCode());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to insert source files", e);
        }
    }

    // 作用：统计指定表行数；逻辑：执行 COUNT 查询并返回首列结果。
    private int countTable(String table) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to count " + table, e);
        }
    }
}
