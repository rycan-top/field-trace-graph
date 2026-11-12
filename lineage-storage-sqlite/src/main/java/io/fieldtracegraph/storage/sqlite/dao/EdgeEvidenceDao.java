package io.fieldtracegraph.storage.sqlite.dao;

import io.fieldtracegraph.core.model.EdgeEvidence;
import io.fieldtracegraph.storage.sqlite.mapping.GraphRowMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 作用：读写一条逻辑边的多份证据。
// 使用场景：证据导入、聚合展示与质量统计。

/**
 * SQL for the additive edge evidence store. A logical edge may have any number
 * of rows; readers aggregate them instead of selecting one winner at write
 * time.
 */
public final class EdgeEvidenceDao {
    private static final String COLUMNS = """
            ev.evidence_id, ev.logical_edge_key, ev.source_edge_id,
            ev.resolution, ev.provenance, ev.evidence_kind,
            ev.adapter, ev.rule, ev.file_id, ev.line_start,
            ev.column_start, ev.line_end, ev.column_end,
            ev.details_json, ev.coverage, ev.observed_at, ev.scope_symbol_id
            """.replaceAll("\\s+", " ").trim();

    private final Connection connection;
    private Boolean tableAvailable;

    // 作用：绑定数据库连接；逻辑：保存连接并延迟探测证据表是否存在。
    public EdgeEvidenceDao(Connection connection) {
        this.connection = connection;
    }

    // 作用：批量写入边证据；逻辑：表不可用或列表为空时返回 0，否则逐条绑定并批量插入。
    public int insert(List<EdgeEvidence> evidence) {
        if (evidence.isEmpty() || !tableAvailable()) {
            return 0;
        }
        String sql = """
                INSERT OR REPLACE INTO edge_evidence(
                    evidence_id, logical_edge_key, scope_symbol_id, source_edge_id, resolution, provenance,
                    evidence_kind, adapter, rule, file_id, line_start, column_start,
                    line_end, column_end, details_json, coverage, observed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (EdgeEvidence item : evidence) {
                statement.setString(1, item.evidenceId());
                statement.setString(2, item.logicalEdgeKey());
                statement.setString(3, item.scopeSymbolId());
                statement.setString(4, item.sourceEdgeId());
                statement.setString(5, item.resolution());
                statement.setString(6, item.provenance());
                statement.setString(7, item.evidenceKind());
                statement.setString(8, item.adapter());
                statement.setString(9, item.rule());
                statement.setString(10, item.fileId());
                statement.setInt(11, item.lineStart());
                statement.setInt(12, item.columnStart());
                statement.setInt(13, item.lineEnd());
                statement.setInt(14, item.columnEnd());
                statement.setString(15, item.detailsJson());
                statement.setString(16, item.coverage());
                statement.setString(17, item.observedAt());
                statement.addBatch();
            }
            statement.executeBatch();
            return evidence.size();
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to insert edge evidence: " + e.getMessage(), e);
        }
    }

    // 作用：按作用域查询边证据；逻辑：连接数据边与源节点，按作用域或节点作用域过滤并排序。
    public List<EdgeEvidence> forScopeIds(List<String> scopeIds) {
        if (scopeIds.isEmpty() || !tableAvailable()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(scopeIds.size(), "?"));
        String query = "SELECT " + COLUMNS
                + " FROM edge_evidence ev"
                + " LEFT JOIN value_data_edge de ON de.edge_id = ev.source_edge_id"
                + " LEFT JOIN value_node sn ON sn.value_id = de.src_value_id"
                + " WHERE ev.scope_symbol_id IN (" + placeholders + ")"
                + " OR sn.scope_symbol_id IN (" + placeholders + ")"
                + " ORDER BY ev.logical_edge_key, ev.evidence_id";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            for (int i = 0; i < scopeIds.size(); i++) {
                statement.setString(i + 1, scopeIds.get(i));
            }
            for (int i = 0; i < scopeIds.size(); i++) {
                statement.setString(scopeIds.size() + i + 1, scopeIds.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<EdgeEvidence> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(GraphRowMapper.edgeEvidence(resultSet));
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Unable to query edge evidence: " + e.getMessage() + " SQL=" + query,
                    e
            );
        }
    }

    // 作用：判断逻辑边是否存在；逻辑：用分隔符拼接逻辑边键后与数据边表比对。
    public boolean logicalEdgeExists(String logicalEdgeKey) {
        String query = """
                SELECT 1
                FROM value_data_edge
                WHERE scope_symbol_id || char(31) || src_value_id || char(31)
                      || dst_value_id || char(31) || edge_type = ?
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, logicalEdgeKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to validate logical edge: " + e.getMessage(), e);
        }
    }

    // 作用：探测证据表是否可用；逻辑：查 sqlite_master 并缓存结果，异常时视为不可用。
    private boolean tableAvailable() {
        if (tableAvailable == null) {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'edge_evidence'")) {
                tableAvailable = resultSet.next();
            } catch (SQLException e) {
                tableAvailable = false;
            }
        }
        return tableAvailable;
    }
}
