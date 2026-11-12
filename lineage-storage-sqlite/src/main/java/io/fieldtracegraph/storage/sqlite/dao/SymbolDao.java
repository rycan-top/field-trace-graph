package io.fieldtracegraph.storage.sqlite.dao;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fieldtracegraph.core.model.MethodCandidate;
import io.fieldtracegraph.core.model.SymbolRecord;
import io.fieldtracegraph.storage.sqlite.mapping.GraphRowMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// 作用：读写符号表。
// 使用场景：索引发布、methods 搜索与调用解析。

/**
 * SQL for the source_code_symbol table, including method candidate lookup and the
 * symbol rows used during publishing.
 */
public final class SymbolDao {
    private final Connection connection;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 作用：绑定数据库连接；逻辑：保存调用方连接供后续符号查询与写入复用。
    public SymbolDao(Connection connection) {
        this.connection = connection;
    }

    // 作用：按名称模糊搜索方法；逻辑：匹配简单名、限定名与声明类型，分页返回候选。
    public List<MethodCandidate> findMethods(String name, int offset, int limit, boolean includeAccessors) {
        String query = """
                SELECT symbol_id, qname, module_id, kind, simple_name, declaring_type, parameter_types_json
                FROM source_code_symbol
                WHERE (simple_name LIKE ? OR qname LIKE ? OR declaring_type LIKE ?)
                  AND kind IN ('METHOD', 'CONSTRUCTOR')
                  AND (symbol_role = 'BUSINESS' OR ? = 1)
                ORDER BY qname
                LIMIT ? OFFSET ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, "%" + name + "%");
            statement.setString(2, "%" + name + "%");
            statement.setString(3, "%" + name + "%");
            statement.setInt(4, includeAccessors ? 1 : 0);
            statement.setInt(5, limit);
            statement.setInt(6, offset);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<MethodCandidate> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(new MethodCandidate(
                            resultSet.getString(1),
                            resultSet.getString(2),
                            resultSet.getString(3),
                            resultSet.getString(4),
                            resultSet.getString(5),
                            resultSet.getString(6),
                            GraphRowMapper.signatureFromQname(resultSet.getString(2))
                    ));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query methods", e);
        }
    }

    // 作用：按方法 ID 查询候选；逻辑：限定方法或构造器类型，未命中返回 null。
    public MethodCandidate findMethodById(String methodId) {
        String query = """
                SELECT symbol_id, qname, module_id, kind, simple_name, declaring_type, parameter_types_json
                FROM source_code_symbol
                WHERE symbol_id = ? AND kind IN ('METHOD', 'CONSTRUCTOR')
                """;
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, methodId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new MethodCandidate(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getString(3),
                        resultSet.getString(4),
                        resultSet.getString(5),
                        resultSet.getString(6),
                        GraphRowMapper.signatureFromQname(resultSet.getString(2))
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query method by id", e);
        }
    }

    /**
     * Resolves a method selector that is either the stable symbol id, a full
     * qualified signature, or a simple signature that uniquely matches one
     * method or constructor. Ambiguous selectors are rejected instead of being
     * silently narrowed to an arbitrary candidate.
     */
    // 作用：按选择器解析唯一方法；逻辑：先尝试 ID，再按签名匹配，多候选时拒绝歧义。
    public MethodCandidate findMethodBySelector(String selector) {
        MethodCandidate byId = findMethodById(selector);
        if (byId != null) {
            return byId;
        }
        String signature = stripModulePrefix(selector);
        List<MethodCandidate> matches = findMethodBySignature(signature);
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("METHOD_AMBIGUOUS");
        }
        return null;
    }

    // 作用：去掉选择器的模块前缀；逻辑：截取双冒号之后的部分，无前缀时原样返回。
    private String stripModulePrefix(String selector) {
        int separator = selector.indexOf("::");
        return separator < 0 ? selector : selector.substring(separator + 2);
    }

    // 作用：按完整或简单签名查询方法；逻辑：同时匹配限定名与井号后的签名部分。
    private List<MethodCandidate> findMethodBySignature(String signature) {
        String query = """
                SELECT symbol_id, qname, module_id, kind, simple_name, declaring_type, parameter_types_json
                FROM source_code_symbol
                WHERE kind IN ('METHOD', 'CONSTRUCTOR')
                  AND (qname = ? OR substr(qname, instr(qname, '#') + 1) = ?)
                ORDER BY qname
                """;
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, signature);
            statement.setString(2, signature);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<MethodCandidate> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(new MethodCandidate(
                            resultSet.getString(1),
                            resultSet.getString(2),
                            resultSet.getString(3),
                            resultSet.getString(4),
                            resultSet.getString(5),
                            resultSet.getString(6),
                            GraphRowMapper.signatureFromQname(resultSet.getString(2))
                    ));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query method by signature", e);
        }
    }

    // 作用：统计匹配方法数量；逻辑：按名称模糊匹配并遵循访问器过滤规则计数。
    public int countMethods(String name, boolean includeAccessors) {
        String query = """
                SELECT COUNT(*)
                FROM source_code_symbol
                WHERE (simple_name LIKE ? OR qname LIKE ? OR declaring_type LIKE ?)
                  AND kind IN ('METHOD', 'CONSTRUCTOR')
                  AND (symbol_role = 'BUSINESS' OR ? = 1)
                """;
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, "%" + name + "%");
            statement.setString(2, "%" + name + "%");
            statement.setString(3, "%" + name + "%");
            statement.setInt(4, includeAccessors ? 1 : 0);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to count methods", e);
        }
    }

    // 作用：由相对路径集合查符号 ID；逻辑：连接源文件表按路径批量过滤并去重。
    public Set<String> symbolIdsByRelativePaths(Set<String> relativePaths) {
        if (relativePaths.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(relativePaths.size(), "?"));
        String query = """
                SELECT symbol.symbol_id
                FROM source_code_symbol symbol
                JOIN source_file file ON file.file_id = symbol.file_id
                WHERE file.relative_path IN (""" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            int index = 1;
            for (String path : relativePaths) {
                statement.setString(index++, path);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                Set<String> result = new LinkedHashSet<>();
                while (resultSet.next()) {
                    result.add(resultSet.getString(1));
                }
                return Set.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query symbols by relative paths", e);
        }
    }

    /**
     * Returns every symbol currently published, including rows inherited from
     * earlier incremental generations. Call-edge validation must use this set
     * rather than only the symbols emitted by the current analysis batch.
     */
    // 作用：读取全部已发布符号 ID；逻辑：扫描符号表并返回不可变集合供调用边校验。
    public Set<String> allSymbolIds() {
        String query = "SELECT symbol_id FROM source_code_symbol";
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            Set<String> result = new LinkedHashSet<>();
            while (resultSet.next()) {
                result.add(resultSet.getString(1));
            }
            return Set.copyOf(result);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query symbol ids", e);
        }
    }

    // 作用：由相对路径集合查全限定名；逻辑：连接源文件表按路径批量过滤并去重。
    public Set<String> qnamesByRelativePaths(Set<String> relativePaths) {
        if (relativePaths.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(relativePaths.size(), "?"));
        String query = """
                SELECT symbol.qname
                FROM source_code_symbol symbol
                JOIN source_file file ON file.file_id = symbol.file_id
                WHERE file.relative_path IN (""" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            int index = 1;
            for (String path : relativePaths) {
                statement.setString(index++, path);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                Set<String> result = new LinkedHashSet<>();
                while (resultSet.next()) {
                    result.add(resultSet.getString(1));
                }
                return Set.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query symbol qnames by relative paths", e);
        }
    }

    // 作用：由符号 ID 反查文件路径；逻辑：连接源文件表按符号 ID 批量查询并去重。
    public Set<String> relativePathsBySymbolIds(Set<String> symbolIds) {
        if (symbolIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(symbolIds.size(), "?"));
        String query = """
                SELECT file.relative_path
                FROM source_code_symbol symbol
                JOIN source_file file ON file.file_id = symbol.file_id
                WHERE symbol.symbol_id IN (""" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            int index = 1;
            for (String symbolId : symbolIds) {
                statement.setString(index++, symbolId);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                Set<String> result = new LinkedHashSet<>();
                while (resultSet.next()) {
                    result.add(resultSet.getString(1));
                }
                return Set.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query relative paths by symbol ids", e);
        }
    }

    // 作用：由符号 ID 批量取限定名；逻辑：按 ID 列表查询并组装为映射。
    public Map<String, String> qnamesBySymbolIds(List<String> symbolIds) {
        if (symbolIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(symbolIds.size(), "?"));
        String query = """
                SELECT symbol_id, qname
                FROM source_code_symbol
                WHERE symbol_id IN (""" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            for (int i = 0; i < symbolIds.size(); i++) {
                statement.setString(i + 1, symbolIds.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, String> result = new HashMap<>();
                while (resultSet.next()) {
                    result.put(resultSet.getString(1), resultSet.getString(2));
                }
                return Map.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to query qnames by symbol ids", e);
        }
    }

    // 作用：统计符号数量；逻辑：委托通用计数方法统计符号表。
    public int count() {
        return countTable("source_code_symbol");
    }

    // 作用：批量写入符号；逻辑：补齐默认角色后绑定参数类型 JSON 并批量插入。
    public void insert(List<SymbolRecord> symbols) {
        String sql = """
                INSERT INTO source_code_symbol(symbol_id, file_id, module_id, kind, symbol_role, qname, simple_name, declaring_type,
                                       parameter_types_json, return_type, parent_symbol_id, line_start, column_start, line_end, column_end)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (SymbolRecord symbol : symbols) {
                statement.setString(1, symbol.symbolId());
                statement.setString(2, symbol.fileId());
                statement.setString(3, symbol.moduleId());
                statement.setString(4, symbol.kind());
                statement.setString(5, symbol.role() == null || symbol.role().isBlank()
                        ? "BUSINESS"
                        : symbol.role());
                statement.setString(6, symbol.qname());
                statement.setString(7, symbol.simpleName());
                statement.setString(8, symbol.declaringType());
                statement.setString(9, json(symbol.parameterTypes()));
                statement.setString(10, symbol.returnType());
                statement.setString(11, symbol.parentSymbolId());
                statement.setInt(12, symbol.lineStart());
                statement.setInt(13, symbol.columnStart());
                statement.setInt(14, symbol.lineEnd());
                statement.setInt(15, symbol.columnEnd());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to insert symbols", e);
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

    // 作用：把对象序列化为 JSON；逻辑：调用对象映射器，失败时抛出状态异常。
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize JSON", e);
        }
    }
}
