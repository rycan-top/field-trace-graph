package io.fieldtracegraph.storage.sqlite.dao;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fieldtracegraph.core.model.CallEdgeRecord;
import io.fieldtracegraph.core.model.PendingRefRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.storage.sqlite.mapping.GraphRowMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：读写方法调用边。
// 使用场景：跨方法追踪与可达范围计算。

/**
 * SQL for parsed_invoke_edge and pending_parse_invoke_ref. Both tables describe call-site
 * facts and unresolved candidates, so they share one DAO.
 */
public final class CallEdgeDao {
    private final Connection connection;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 作用：绑定数据库连接；逻辑：保存调用方连接并复用其事务上下文。
    public CallEdgeDao(Connection connection) {
        this.connection = connection;
    }

    // 作用：按调用方查询调用边；逻辑：动态拼占位符批量查询并按调用方与边 ID 排序。
    public List<CallEdgeRecord> callEdgesForScopeIds(List<String> callerSymbolIds) {
        if (callerSymbolIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(callerSymbolIds.size(), "?"));
        String query = """
                SELECT edge_id, call_site_id, caller_symbol_id, callee_symbol_id, target_qname,
                       dispatch_kind, resolution, receiver_value_id, arguments_json, result_value_id,
                       file_id, line_start, column_start, line_end, column_end
                FROM parsed_invoke_edge
                WHERE caller_symbol_id IN (""" + placeholders + ")\nORDER BY caller_symbol_id, edge_id";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            for (int i = 0; i < callerSymbolIds.size(); i++) {
                statement.setString(i + 1, callerSymbolIds.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<CallEdgeRecord> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(GraphRowMapper.callEdge(resultSet));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query call edges for scopes", e);
        }
    }

    // 作用：按调用方查询未解析引用；逻辑：批量读取待解析调用并按位置排序。
    public List<PendingRefRecord> pendingRefsForScopeIds(List<String> callerSymbolIds) {
        if (callerSymbolIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(callerSymbolIds.size(), "?"));
        String query = """
                SELECT pending_id, call_site_id, caller_symbol_id, reference_kind, reference_name,
                       reason, candidates_json, file_id, line_start, column_start, line_end, column_end,
                       dispatch_kind, receiver_value_id, arguments_json, result_value_id
                FROM pending_parse_invoke_ref
                WHERE caller_symbol_id IN (""" + placeholders + ")\nORDER BY caller_symbol_id, line_start, column_start";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            for (int i = 0; i < callerSymbolIds.size(); i++) {
                statement.setString(i + 1, callerSymbolIds.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<PendingRefRecord> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(GraphRowMapper.pendingRef(resultSet));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query pending refs for scopes", e);
        }
    }

    /**
     * Unresolved calls live only in {@code pending_parse_invoke_ref}; the call graph is
     * reserved for calls that reached a callee. The traversal still needs the
     * call-site payload of an unresolved call to keep receiver-to-result
     * bridges, so the pending rows are projected back into call edges here.
     */
    // 作用：把未解析调用投影为调用边；逻辑：跳过方法引用，其余生成稳定的未解析边 ID。
    public List<CallEdgeRecord> unresolvedCallEdgesForScopeIds(List<String> callerSymbolIds) {
        List<CallEdgeRecord> result = new ArrayList<>();
        for (PendingRefRecord ref : pendingRefsForScopeIds(callerSymbolIds)) {
            if ("METHOD_REFERENCE".equals(ref.referenceKind())) {
                continue;
            }
            result.add(ref.toUnresolvedCallEdge(
                    Ids.sha256(ref.pendingId(), "unresolved-call"),
                    ref.referenceName()
            ));
        }
        return result;
    }

    // 作用：构建被调方到调用方的映射；逻辑：扫描调用边并按被调方聚合调用方集合。
    public Map<String, Set<String>> callersByCallee() {
        String query = "SELECT callee_symbol_id, caller_symbol_id FROM parsed_invoke_edge WHERE callee_symbol_id IS NOT NULL";
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(query)) {
            Map<String, Set<String>> result = new HashMap<>();
            while (resultSet.next()) {
                result.computeIfAbsent(resultSet.getString(1), key -> new LinkedHashSet<>()).add(resultSet.getString(2));
            }
            return immutable(result);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query callers by callee", e);
        }
    }

    // 作用：构建引用名到调用方的映射；逻辑：扫描未解析引用并按引用名聚合调用方。
    public Map<String, Set<String>> pendingRefCallersByReferenceName() {
        String query = "SELECT reference_name, caller_symbol_id FROM pending_parse_invoke_ref";
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(query)) {
            Map<String, Set<String>> result = new HashMap<>();
            while (resultSet.next()) {
                result.computeIfAbsent(resultSet.getString(1), key -> new LinkedHashSet<>()).add(resultSet.getString(2));
            }
            return immutable(result);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query pending ref callers", e);
        }
    }

    // 作用：批量写入调用边；逻辑：绑定调用点、分发方式与参数 JSON 后批量插入。
    public void insertCallEdges(List<CallEdgeRecord> edges) {
        String sql = """
                INSERT INTO parsed_invoke_edge(edge_id, call_site_id, caller_symbol_id, callee_symbol_id, target_qname,
                                          dispatch_kind, resolution, receiver_value_id, arguments_json, result_value_id,
                                          file_id, line_start, column_start, line_end, column_end)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (CallEdgeRecord edge : edges) {
                statement.setString(1, edge.edgeId());
                statement.setString(2, edge.callSiteId());
                statement.setString(3, edge.callerSymbolId());
                statement.setString(4, edge.calleeSymbolId());
                statement.setString(5, edge.targetQname());
                statement.setString(6, edge.dispatchKind());
                statement.setString(7, edge.resolution());
                statement.setString(8, edge.receiverValueId());
                statement.setString(9, json(edge.argumentValueIds()));
                statement.setString(10, edge.resultValueId());
                statement.setString(11, edge.fileId());
                statement.setInt(12, edge.lineStart());
                statement.setInt(13, edge.columnStart());
                statement.setInt(14, edge.lineEnd());
                statement.setInt(15, edge.columnEnd());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to insert call edges", e);
        }
    }

    // 作用：批量写入未解析引用；逻辑：补齐默认分发方式后序列化候选与参数并插入。
    public void insertPendingRefs(List<PendingRefRecord> refs) {
        String sql = """
                INSERT INTO pending_parse_invoke_ref(pending_id, call_site_id, caller_symbol_id, reference_kind,
                                            reference_name, reason, candidates_json, file_id,
                                            line_start, column_start, line_end, column_end,
                                            dispatch_kind, receiver_value_id, arguments_json, result_value_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (PendingRefRecord ref : refs) {
                statement.setString(1, ref.pendingId());
                statement.setString(2, ref.callSiteId());
                statement.setString(3, ref.callerSymbolId());
                statement.setString(4, ref.referenceKind());
                statement.setString(5, ref.referenceName());
                statement.setString(6, ref.reason());
                statement.setString(7, json(ref.candidates()));
                statement.setString(8, ref.fileId());
                statement.setInt(9, ref.lineStart());
                statement.setInt(10, ref.columnStart());
                statement.setInt(11, ref.lineEnd());
                statement.setInt(12, ref.columnEnd());
                statement.setString(13, ref.dispatchKind() == null || ref.dispatchKind().isBlank()
                        ? "VIRTUAL"
                        : ref.dispatchKind());
                statement.setString(14, ref.receiverValueId());
                statement.setString(15, json(ref.argumentValueIds() == null ? List.of() : ref.argumentValueIds()));
                statement.setString(16, ref.resultValueId());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to insert pending refs", e);
        }
    }

    // 作用：冻结嵌套集合；逻辑：逐项复制为不可变集合并返回不可变映射。
    private Map<String, Set<String>> immutable(Map<String, Set<String>> source) {
        Map<String, Set<String>> result = new HashMap<>();
        for (var entry : source.entrySet()) {
            result.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(result);
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
