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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // 作用：按作用域查询边证据；逻辑：分别按证据作用域与源节点作用域走索引查询后合并去重。
    public List<EdgeEvidence> forScopeIds(List<String> scopeIds) {
        if (scopeIds.isEmpty() || !tableAvailable()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(scopeIds.size(), "?"));
        String direct = "SELECT " + COLUMNS
                + " FROM edge_evidence ev"
                + " WHERE ev.scope_symbol_id IN (" + placeholders + ")";
        String bridged = "SELECT " + COLUMNS
                + " FROM value_node sn"
                + " JOIN value_data_edge de ON de.src_value_id = sn.value_id"
                + " JOIN edge_evidence ev ON ev.source_edge_id = de.edge_id"
                + " WHERE sn.scope_symbol_id IN (" + placeholders + ")";
        Map<String, EdgeEvidence> merged = new LinkedHashMap<>();
        try {
            collect(direct, scopeIds, merged, 0L);
            collect(bridged, scopeIds, merged, 0L);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query edge evidence: " + e.getMessage(), e);
        }
        List<EdgeEvidence> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparing(EdgeEvidence::logicalEdgeKey)
                .thenComparing(EdgeEvidence::evidenceId));
        return List.copyOf(result);
    }

    // 作用：执行单个证据查询并合并结果；逻辑：逐行检查预算，按 evidence_id 去重保留首行。
    private void collect(
            String query,
            List<String> scopeIds,
            Map<String, EdgeEvidence> merged,
            long deadlineNanos
    ) throws SQLException {
        try (PreparedStatement scoped = connection.prepareStatement(query)) {
            for (int i = 0; i < scopeIds.size(); i++) {
                scoped.setString(i + 1, scopeIds.get(i));
            }
            try (ResultSet resultSet = scoped.executeQuery()) {
                int seen = 0;
                while (resultSet.next()) {
                    /*
                     * A single IN-list can scan a whole index segment. The
                     * JDBC progress handler is not guaranteed to fire between
                     * rows, so the row loop owns the hard deadline: a wide
                     * evidence scan must abort inside the query budget.
                     */
                    if ((++seen & 0x3FF) == 0) {
                        ensureBudget(deadlineNanos);
                    }
                    EdgeEvidence evidence = GraphRowMapper.edgeEvidence(resultSet);
                    merged.putIfAbsent(evidence.evidenceId(), evidence);
                }
            }
        }
    }

    // 作用：按已加载边的证据/边 ID 取证据；逻辑：优先用 evidence_id 与 source_edge_id 分批查询，
    // 避免按作用域全量加载大仓库证据导致堆溢出与长时间扫描。
    public List<EdgeEvidence> forEdgeRefs(List<String> evidenceIds, List<String> sourceEdgeIds) {
        return forEdgeRefs(evidenceIds, sourceEdgeIds, 0L);
    }

    // 作用：带预算地取证据；逻辑：每个分批查询前检查截止时间，超时就中止整个加载阶段。
    public List<EdgeEvidence> forEdgeRefs(
            List<String> evidenceIds,
            List<String> sourceEdgeIds,
            long deadlineNanos
    ) {
        if (!tableAvailable()) {
            return List.of();
        }
        Map<String, EdgeEvidence> merged = new LinkedHashMap<>();
        try {
            for (List<String> chunk : chunks(evidenceIds)) {
                ensureBudget(deadlineNanos);
                String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
                collect("SELECT " + COLUMNS + " FROM edge_evidence ev WHERE ev.evidence_id IN ("
                        + placeholders + ")", chunk, merged, deadlineNanos);
            }
            for (List<String> chunk : chunks(sourceEdgeIds)) {
                ensureBudget(deadlineNanos);
                String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
                collect("SELECT " + COLUMNS + " FROM edge_evidence ev WHERE ev.source_edge_id IN ("
                        + placeholders + ")", chunk, merged, deadlineNanos);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query edge evidence: " + e.getMessage(), e);
        }
        List<EdgeEvidence> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparing(EdgeEvidence::logicalEdgeKey)
                .thenComparing(EdgeEvidence::evidenceId));
        return List.copyOf(result);
    }

    // 作用：检查查询预算；逻辑：截止时间为 0 表示不限时，超时抛出可识别的中断异常。
    private void ensureBudget(long deadlineNanos) {
        if (deadlineNanos > 0 && System.nanoTime() > deadlineNanos) {
            throw new IllegalStateException("query budget interrupted during evidence loading");
        }
    }

    // 作用：切分 ID 批次；逻辑：去空去重后按固定大小分块，避免 SQLite 变量数量上限。
    private List<List<String>> chunks(List<String> ids) {
        List<String> distinct = ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        List<List<String>> result = new ArrayList<>();
        for (int start = 0; start < distinct.size(); start += 400) {
            result.add(distinct.subList(start, Math.min(distinct.size(), start + 400)));
        }
        return result;
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
