package io.fieldtracegraph.storage.sqlite;

import io.fieldtracegraph.core.model.IndexStatusResult;
import io.fieldtracegraph.core.model.IndexQuality;
import io.fieldtracegraph.core.model.SymbolStatistics;
import io.fieldtracegraph.core.port.IndexStateReader;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

// 作用：读取索引状态、统计与质量指标。
// 使用场景：status 命令、增量判定与边界原因分布。

public final class SqliteIndexStateReader implements IndexStateReader {
    private final Connection connection;

    // 作用：绑定数据库连接；逻辑：保存连接供状态与质量查询使用。
    public SqliteIndexStateReader(Connection connection) {
        this.connection = connection;
    }

    // 作用：判断索引是否可用；逻辑：图状态为 READY 时视为可用。
    @Override
    public boolean isReady() {
        return "READY".equals(graphStatus());
    }

    // 作用：检查 schema 是否初始化；逻辑：查询 sqlite_master 中是否存在版本表。
    @Override
    public boolean isSchemaInitialized() {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_version'")) {
            return resultSet.next();
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to inspect SQLite schema", e);
        }
    }

    // 作用：读取索引代数；逻辑：查索引状态单例行，缺失时返回 0。
    @Override
    public int generation() {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT active_generation FROM full_index_state WHERE singleton_id = 1")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read index generation", e);
        }
    }

    // 作用：读取图状态；逻辑：查索引状态单例行，缺失时返回 EMPTY。
    @Override
    public String graphStatus() {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT graph_status FROM full_index_state WHERE singleton_id = 1")) {
            return resultSet.next() ? resultSet.getString(1) : "EMPTY";
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read graph status", e);
        }
    }

    // 作用：读取上下文指纹；逻辑：查索引状态单例行的 context_hash。
    @Override
    public String contextHash() {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT context_hash FROM full_index_state WHERE singleton_id = 1")) {
            return resultSet.next() ? resultSet.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read index context hash", e);
        }
    }

    // 作用：读取项目根指纹；逻辑：查索引状态单例行的路径指纹。
    @Override
    public String projectRootFingerprint() {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT project_root_fingerprint FROM full_index_state WHERE singleton_id = 1")) {
            return resultSet.next() ? resultSet.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read project root fingerprint", e);
        }
    }

    // 作用：组装状态结果；逻辑：读取状态行并附带各表计数与符号统计，空库返回初始值。
    @Override
    public IndexStatusResult toStatusResult(String freshness) {
        String query = """
                SELECT active_generation, graph_status, context_hash, parser_version, project_root_fingerprint,
                       last_attempt_status, last_error_code, last_error_message
                FROM full_index_state
                WHERE singleton_id = 1
                """;
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(query)) {
            if (!resultSet.next()) {
                return new IndexStatusResult(0, "EMPTY", freshness, "0.1.0", null, null, "IDLE", null, null,
                        0, 0, SymbolStatistics.empty(), 0, 0, 0, 0);
            }
            return new IndexStatusResult(
                    resultSet.getInt(1),
                    resultSet.getString(2),
                    freshness,
                    resultSet.getString(4),
                    resultSet.getString(3),
                    resultSet.getString(5),
                    resultSet.getString(6),
                    resultSet.getString(7),
                    resultSet.getString(8),
                    count("source_file"),
                    count("source_code_symbol"),
                    symbolStatistics(),
                    count("value_node"),
                    count("value_data_edge"),
                    count("parsed_invoke_edge"),
                    count("pending_parse_invoke_ref")
            );
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read index status", e);
        }
    }

    // 作用：统计索引质量；逻辑：按解析级别聚合边、计算比例与边界密度并汇总原因分布。
    @Override
    public IndexQuality qualityStatistics() {
        int exact = 0;
        int candidate = 0;
        int unresolved = 0;
        int runtimeObserved = 0;
        String query = resolutionQuery();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            while (resultSet.next()) {
                int rank = resultSet.getInt(1);
                int count = resultSet.getInt(2);
                switch (rank) {
                    case 4 -> runtimeObserved += count;
                    case 3 -> exact += count;
                    case 2 -> candidate += count;
                    default -> unresolved += count;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read resolution statistics", e);
        }
        int total = exact + candidate + unresolved + runtimeObserved;
        int methods = scalarCount(
                "SELECT COUNT(*) FROM source_code_symbol WHERE kind = 'METHOD'"
        );
        int openBoundaries = scalarCount(
                "SELECT COUNT(*) FROM pending_parse_invoke_ref WHERE reason <> 'PLATFORM_LIBRARY'"
        );
        double density = methods == 0
                ? 0d
                : (double) openBoundaries * 1000d / methods;
        List<IndexQuality.BoundaryReasonCount> reasons = boundaryReasons();
        int frameworkAdapterCandidates = scalarCount(
                "SELECT COUNT(*) FROM value_data_edge "
                        + "WHERE resolution = 'CANDIDATE' AND provenance = 'FRAMEWORK_ADAPTER'"
        );
        return new IndexQuality(
                ratio(exact + runtimeObserved, total),
                ratio(candidate, total),
                ratio(unresolved, total),
                exact,
                candidate,
                unresolved,
                runtimeObserved,
                frameworkAdapterCandidates,
                ratio(frameworkAdapterCandidates, total),
                Math.max(0, candidate - frameworkAdapterCandidates),
                ratio(Math.max(0, candidate - frameworkAdapterCandidates), total),
                openBoundaries,
                density,
                reasons.stream().limit(5).toList(),
                reasons
        );
    }

    // 作用：生成解析级别统计 SQL；逻辑：无证据表时直接按边聚合，否则合并证据与逻辑边取较高等级。
    private String resolutionQuery() {
        if (!tableExists("edge_evidence")) {
            return """
                    SELECT CASE resolution
                               WHEN 'RUNTIME_OBSERVED' THEN 4
                               WHEN 'EXACT_STATIC' THEN 3
                               WHEN 'COMPILE_TIME_TARGET' THEN 3
                               WHEN 'VIRTUAL_UNIQUE' THEN 3
                               WHEN 'CANDIDATE' THEN 2
                               ELSE 1
                           END AS resolution_rank,
                           COUNT(*)
                    FROM value_data_edge
                    GROUP BY resolution_rank
                    """;
        }
        return """
                WITH logical_edges AS (
                    SELECT scope_symbol_id, src_value_id, dst_value_id, edge_type,
                           MAX(CASE resolution
                               WHEN 'RUNTIME_OBSERVED' THEN 4
                               WHEN 'EXACT_STATIC' THEN 3
                               WHEN 'COMPILE_TIME_TARGET' THEN 3
                               WHEN 'VIRTUAL_UNIQUE' THEN 3
                               WHEN 'CANDIDATE' THEN 2
                               ELSE 1
                           END) AS base_rank
                    FROM value_data_edge
                    GROUP BY scope_symbol_id, src_value_id, dst_value_id, edge_type
                ),
                evidence_rank AS (
                    SELECT logical_edge_key,
                           MAX(CASE resolution
                               WHEN 'RUNTIME_OBSERVED' THEN 4
                               WHEN 'EXACT_STATIC' THEN 3
                               WHEN 'CANDIDATE' THEN 2
                               ELSE 1
                           END) AS evidence_rank
                    FROM edge_evidence
                    GROUP BY logical_edge_key
                ),
                merged AS (
                    SELECT COALESCE(evidence_rank.evidence_rank, logical_edges.base_rank)
                               AS resolution_rank
                    FROM logical_edges
                    LEFT JOIN evidence_rank
                      ON evidence_rank.logical_edge_key =
                         logical_edges.scope_symbol_id || char(31)
                         || logical_edges.src_value_id || char(31)
                         || logical_edges.dst_value_id || char(31)
                         || logical_edges.edge_type
                )
                SELECT resolution_rank, COUNT(*)
                FROM merged
                GROUP BY resolution_rank
                """;
    }

    // 作用：判断表是否存在；逻辑：按表名查询 sqlite_master。
    private boolean tableExists(String table) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to inspect schema table " + table, e);
        }
    }

    // 作用：统计边界原因分布；逻辑：排除平台库后按原因分组并按数量倒序返回。
    private List<IndexQuality.BoundaryReasonCount> boundaryReasons() {
        String query = """
                SELECT reason, COUNT(*) AS reason_count
                FROM pending_parse_invoke_ref
                WHERE reason <> 'PLATFORM_LIBRARY'
                GROUP BY reason
                ORDER BY reason_count DESC, reason
                """;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            List<IndexQuality.BoundaryReasonCount> result = new ArrayList<>();
            while (resultSet.next()) {
                result.add(new IndexQuality.BoundaryReasonCount(
                        resultSet.getString(1),
                        resultSet.getInt(2)
                ));
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read boundary statistics", e);
        }
    }

    // 作用：执行单值计数查询；逻辑：运行 SQL 取首行首列，无结果时返回 0。
    private int scalarCount(String query) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read quality count", e);
        }
    }

    // 作用：计算比例；逻辑：分母为 0 时返回 0，否则返回浮点商。
    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0d : (double) numerator / denominator;
    }

    // 作用：统计符号构成；逻辑：按类型、方法、业务方法、访问器、构造器与初始化器聚合。
    private SymbolStatistics symbolStatistics() {
        String query = """
                SELECT
                    COUNT(*) AS total_count,
                    COALESCE(SUM(CASE WHEN kind IN (
                        'CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'ANNOTATION'
                    ) THEN 1 ELSE 0 END), 0) AS type_count,
                    COALESCE(SUM(CASE WHEN kind = 'METHOD' THEN 1 ELSE 0 END), 0) AS method_count,
                    COALESCE(SUM(CASE WHEN kind = 'METHOD' AND symbol_role = 'BUSINESS'
                        THEN 1 ELSE 0 END), 0) AS business_method_count,
                    COALESCE(SUM(CASE WHEN symbol_role IN ('ACCESSOR_GET', 'ACCESSOR_SET')
                        THEN 1 ELSE 0 END), 0) AS accessor_count,
                    COALESCE(SUM(CASE WHEN kind = 'CONSTRUCTOR' THEN 1 ELSE 0 END), 0) AS constructor_count,
                    COALESCE(SUM(CASE WHEN symbol_role = 'INITIALIZER' THEN 1 ELSE 0 END), 0)
                        AS initializer_count
                FROM source_code_symbol
                """;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            if (!resultSet.next()) {
                return SymbolStatistics.empty();
            }
            return new SymbolStatistics(
                    resultSet.getInt("total_count"),
                    resultSet.getInt("type_count"),
                    resultSet.getInt("method_count"),
                    resultSet.getInt("business_method_count"),
                    resultSet.getInt("accessor_count"),
                    resultSet.getInt("constructor_count"),
                    resultSet.getInt("initializer_count")
            );
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read symbol statistics", e);
        }
    }

    // 作用：统计指定表行数；逻辑：执行 COUNT 查询并返回首列结果。
    private int count(String table) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to count " + table, e);
        }
    }
}
