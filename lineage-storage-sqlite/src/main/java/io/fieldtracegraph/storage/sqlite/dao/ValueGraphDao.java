package io.fieldtracegraph.storage.sqlite.dao;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.storage.sqlite.mapping.GraphRowMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

// 作用：读写值节点与数据边。
// 使用场景：图构建、遍历与 CSV/JSON 导出。

/**
 * SQL for value_node and value_data_edge. The two tables form the
 * method-internal value graph and are read and written together.
 */
public final class ValueGraphDao {
    private static final String VALUE_NODE_COLUMNS = """
            value_id, scope_symbol_id, file_id, value_kind, canonical_value, root_name, access_path_json,
            declaration_offset, definition_ordinal, literal_kind, line_start, column_start, line_end, column_end
            """.replaceAll("\\s+", " ").trim();
    private static final String ORIGIN_COLUMNS =
            "origin_file, origin_line_start, origin_column_start, origin_line_end, origin_column_end";

    private final Connection connection;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Boolean originColumnsAvailable;
    private Boolean provenanceColumnAvailable;

    // 作用：绑定数据库连接；逻辑：保存连接并延迟探测可选列是否存在。
    public ValueGraphDao(Connection connection) {
        this.connection = connection;
    }

    // 作用：查询单个方法的值节点；逻辑：按作用域过滤并按规范值排序。
    public List<ValueNodeRecord> nodesForScope(String methodId) {
        String query = "SELECT " + valueNodeColumns()
                + " FROM value_node"
                + " WHERE scope_symbol_id = ?"
                + " ORDER BY canonical_value";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, methodId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ValueNodeRecord> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(GraphRowMapper.valueNode(resultSet));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query value nodes", e);
        }
    }

    // 作用：批量查询值节点；逻辑：按作用域 ID 列表动态查询并排序。
    public List<ValueNodeRecord> valueNodesForScopeIds(List<String> scopeIds) {
        if (scopeIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(scopeIds.size(), "?"));
        String query = "SELECT " + valueNodeColumns()
                + " FROM value_node"
                + " WHERE scope_symbol_id IN (" + placeholders + ")"
                + " ORDER BY scope_symbol_id, canonical_value";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            for (int i = 0; i < scopeIds.size(); i++) {
                statement.setString(i + 1, scopeIds.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ValueNodeRecord> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(GraphRowMapper.valueNode(resultSet));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query value nodes for scopes", e);
        }
    }

    // 作用：批量查询数据边；逻辑：连接源节点按作用域过滤，并按兼容性附加来源列。
    public List<DataEdgeRecord> dataEdgesForScopeIds(List<String> scopeIds) {
        if (scopeIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(scopeIds.size(), "?"));
        String query = """
                SELECT edge.edge_id, edge.scope_symbol_id, edge.src_value_id, edge.dst_value_id, edge.edge_type,
                       edge.operation, edge.resolution, edge.evidence_id, edge.branch_id, edge.branch_condition,
                       edge.file_id, edge.line_start, edge.column_start, edge.line_end, edge.column_end, edge.statement_ordinal
                """ + (provenanceColumnAvailable() ? ", edge.provenance " : " ") + """
                FROM value_data_edge edge
                JOIN value_node source_node ON source_node.value_id = edge.src_value_id
                WHERE source_node.scope_symbol_id IN (""" + placeholders + ")\nORDER BY source_node.scope_symbol_id, edge.edge_id";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            for (int i = 0; i < scopeIds.size(); i++) {
                statement.setString(i + 1, scopeIds.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<DataEdgeRecord> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(GraphRowMapper.dataEdge(resultSet));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query data edges for scopes", e);
        }
    }

    // 作用：统计值节点数量；逻辑：委托通用计数方法统计节点表。
    public int valueNodeCount() {
        return countTable("value_node");
    }

    // 作用：统计数据边数量；逻辑：委托通用计数方法统计边表。
    public int dataEdgeCount() {
        return countTable("value_data_edge");
    }

    // 作用：批量写入值节点；逻辑：逐条绑定访问路径与可选来源位置后批量插入。
    public void insertValueNodes(List<ValueNodeRecord> nodes) {
        String sql = """
                INSERT INTO value_node(value_id, scope_symbol_id, file_id, value_kind, canonical_value,
                                           root_name, access_path_json, declaration_offset, definition_ordinal,
                                           literal_kind, line_start, column_start, line_end, column_end,
                                           origin_file, origin_line_start, origin_column_start, origin_line_end, origin_column_end)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (ValueNodeRecord node : nodes) {
                statement.setString(1, node.valueId());
                statement.setString(2, node.scopeSymbolId());
                statement.setString(3, node.fileId());
                statement.setString(4, node.valueKind());
                statement.setString(5, node.canonicalValue());
                statement.setString(6, node.rootName());
                statement.setString(7, json(node.accessPath()));
                statement.setInt(8, node.declarationOffset());
                statement.setInt(9, node.definitionOrdinal());
                statement.setString(10, node.literalKind());
                statement.setInt(11, node.lineStart());
                statement.setInt(12, node.columnStart());
                statement.setInt(13, node.lineEnd());
                statement.setInt(14, node.columnEnd());
                statement.setString(15, node.originFile());
                statement.setInt(16, node.originLineStart());
                statement.setInt(17, node.originColumnStart());
                statement.setInt(18, node.originLineEnd());
                statement.setInt(19, node.originColumnEnd());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to insert value nodes: " + e.getMessage(), e);
        }
    }

    /**
     * A read-only session can be opened against an index produced by an older
     * binary that did not persist node origins. Keep those snapshots queryable
     * until the next synchronisation migrates the table.
     */
    // 作用：按列兼容性拼装值节点字段；逻辑：旧库缺少来源列时只返回基础字段。
    private String valueNodeColumns() {
        return originColumnsAvailable() ? VALUE_NODE_COLUMNS + ", " + ORIGIN_COLUMNS : VALUE_NODE_COLUMNS;
    }

    // 作用：探测值节点来源列是否存在；逻辑：读表结构并缓存结果，异常时视为不存在。
    private boolean originColumnsAvailable() {
        if (originColumnsAvailable == null) {
            originColumnsAvailable = false;
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("PRAGMA table_info(value_node)")) {
                while (resultSet.next()) {
                    if ("origin_file".equalsIgnoreCase(resultSet.getString("name"))) {
                        originColumnsAvailable = true;
                        break;
                    }
                }
            } catch (SQLException e) {
                originColumnsAvailable = false;
            }
        }
        return originColumnsAvailable;
    }

    // 作用：批量写入数据边；逻辑：绑定边类型、解析级别、分支与位置后批量插入。
    public void insertDataEdges(List<DataEdgeRecord> edges) {
        String sql = """
                INSERT INTO value_data_edge(edge_id, scope_symbol_id, src_value_id, dst_value_id, edge_type, operation,
                                          resolution, provenance, evidence_id, branch_id, branch_condition, file_id,
                                          line_start, column_start, line_end, column_end, statement_ordinal)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (DataEdgeRecord edge : edges) {
                statement.setString(1, edge.edgeId());
                statement.setString(2, edge.scopeSymbolId());
                statement.setString(3, edge.srcValueId());
                statement.setString(4, edge.dstValueId());
                statement.setString(5, edge.edgeType());
                statement.setString(6, edge.operation());
                statement.setString(7, edge.resolution());
                statement.setString(8, edge.provenance());
                statement.setString(9, edge.evidenceId());
                statement.setString(10, edge.branchId());
                statement.setString(11, edge.branchCondition());
                statement.setString(12, edge.fileId());
                statement.setInt(13, edge.lineStart());
                statement.setInt(14, edge.columnStart());
                statement.setInt(15, edge.lineEnd());
                statement.setInt(16, edge.columnEnd());
                statement.setInt(17, edge.statementOrdinal());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to insert data edges: " + e.getMessage(), e);
        }
    }

    // 作用：探测数据边来源列是否存在；逻辑：读表结构并缓存结果，异常时视为不存在。
    private boolean provenanceColumnAvailable() {
        if (provenanceColumnAvailable == null) {
            provenanceColumnAvailable = false;
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("PRAGMA table_info(value_data_edge)")) {
                while (resultSet.next()) {
                    if ("provenance".equalsIgnoreCase(resultSet.getString("name"))) {
                        provenanceColumnAvailable = true;
                        break;
                    }
                }
            } catch (SQLException e) {
                provenanceColumnAvailable = false;
            }
        }
        return provenanceColumnAvailable;
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

    // 作用：把对象序列化为 JSON；逻辑：调用对象映射器，失败时抛出状态异常。
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize JSON", e);
        }
    }
}
