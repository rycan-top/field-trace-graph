package io.fieldtracegraph.storage.sqlite.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fieldtracegraph.core.model.MethodSummaryRecord;
import io.fieldtracegraph.core.model.ParameterMutationRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

// 作用：读写方法级摘要。
// 使用场景：方法搜索与概览展示。

/**
 * SQL for method_summary. Summaries are written during publishing and
 * read only through the value graph, so this DAO currently owns the insert.
 */
public final class MethodSummaryDao {
    private final Connection connection;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 作用：绑定数据库连接；逻辑：保存调用方传入的连接，事务由外层控制。
    public MethodSummaryDao(Connection connection) {
        this.connection = connection;
    }

    // 作用：批量写入方法摘要；逻辑：序列化参数变更 JSON 后按批次插入摘要表。
    public void insert(List<MethodSummaryRecord> summaries) {
        String sql = """
                INSERT INTO method_summary(summary_id, scope_symbol_id, file_id, parameters_json, return_value_id,
                                              return_order, branch_condition, field_writes_json, parameter_mutations_json,
                                              line_start, column_start, line_end, column_end)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (MethodSummaryRecord summary : summaries) {
                statement.setString(1, summary.summaryId());
                statement.setString(2, summary.scopeSymbolId());
                statement.setString(3, summary.fileId());
                statement.setString(4, summary.parametersJson());
                statement.setString(5, summary.returnValueId());
                statement.setInt(6, summary.returnOrder());
                statement.setString(7, summary.branchCondition());
                statement.setString(8, summary.fieldWritesJson());
                statement.setString(9, json(summary.parameterMutations()));
                statement.setInt(10, summary.lineStart());
                statement.setInt(11, summary.columnStart());
                statement.setInt(12, summary.lineEnd());
                statement.setInt(13, summary.columnEnd());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to insert method summaries", e);
        }
    }

    // 作用：按作用域 ID 查询方法摘要；逻辑：动态拼占位符批量查询并按作用域与返回顺序排序。
    public List<MethodSummaryRecord> summariesForScopeIds(List<String> scopeIds) {
        if (scopeIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(scopeIds.size(), "?"));
        String sql = """
                SELECT summary_id, scope_symbol_id, file_id, parameters_json, return_value_id,
                       return_order, branch_condition, field_writes_json, parameter_mutations_json,
                       line_start, column_start, line_end, column_end
                FROM method_summary
                WHERE scope_symbol_id IN (""" + placeholders + """
                )
                ORDER BY scope_symbol_id, return_order
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < scopeIds.size(); index++) {
                statement.setString(index + 1, scopeIds.get(index));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<MethodSummaryRecord> result = new java.util.ArrayList<>();
                while (resultSet.next()) {
                    result.add(new MethodSummaryRecord(
                            resultSet.getString("summary_id"),
                            resultSet.getString("scope_symbol_id"),
                            resultSet.getString("file_id"),
                            resultSet.getString("parameters_json"),
                            resultSet.getString("return_value_id"),
                            resultSet.getInt("return_order"),
                            resultSet.getString("branch_condition"),
                            resultSet.getString("field_writes_json"),
                            parameterMutations(resultSet.getString("parameter_mutations_json")),
                            resultSet.getInt("line_start"),
                            resultSet.getInt("column_start"),
                            resultSet.getInt("line_end"),
                            resultSet.getInt("column_end")
                    ));
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query method summaries", e);
        }
    }

    // 作用：把参数变更记录序列化为 JSON；逻辑：调用对象映射器写字符串，失败时抛出状态异常。
    private String json(List<ParameterMutationRecord> mutations) {
        try {
            return objectMapper.writeValueAsString(mutations);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize parameter mutations", e);
        }
    }

    // 作用：解析参数变更 JSON；逻辑：空值返回空列表，否则反序列化为记录集合。
    private List<ParameterMutationRecord> parameterMutations(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    value,
                    new TypeReference<List<ParameterMutationRecord>>() {
                    }
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to parse parameter mutations", e);
        }
    }
}
