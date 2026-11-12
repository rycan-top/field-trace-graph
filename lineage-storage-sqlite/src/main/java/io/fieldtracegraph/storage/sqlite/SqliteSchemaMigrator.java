package io.fieldtracegraph.storage.sqlite;

import io.fieldtracegraph.core.support.Ids;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// 作用：建表并执行在线表结构迁移。
// 使用场景：首次运行、升级版本或表定义变化时。

public final class SqliteSchemaMigrator {
    private static final List<String> SYMBOL_DEPENDENT_TABLES = List.of(
            "value_data_edge",
            "parsed_invoke_edge",
            "pending_parse_invoke_ref",
            "value_node",
            "method_summary"
    );

    // 作用：建表并升级表结构；逻辑：读版本行，首次建库，校验和变化时重建受影响的表并复核外键。
    public void migrate(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            VersionRow existing = readVersionRow(connection);
            verifyVersionRow(existing);
            if (existing == null) {
                applySchema(statement);
                statement.executeUpdate("INSERT INTO schema_version(version, applied_at, migration_checksum) VALUES (1, '"
                        + Instant.now() + "', '" + schemaChecksum() + "')");
                statement.executeUpdate("INSERT OR IGNORE INTO full_index_state(singleton_id, active_generation, graph_status, parser_version, project_root_fingerprint, last_attempt_status) "
                        + "VALUES (1, 0, 'EMPTY', '0.1.0', '', 'IDLE')");
            } else if (!schemaChecksum().equals(existing.checksum())) {
                migrateChangedTables(connection, statement);
                applySchema(statement);
                verifyForeignKeys(statement);
                moveUnresolvedCallsToPendingRefs(statement);
                statement.executeUpdate("UPDATE schema_version SET applied_at = '"
                        + Instant.now() + "', migration_checksum = '" + schemaChecksum()
                        + "' WHERE version = 1");
            } else {
                applySchema(statement);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to initialize SQLite schema: " + e.getMessage(), e);
        }
    }

    // 作用：判断表是否存在；逻辑：查询 sqlite_master 中指定名称的表。
    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    // 作用：读取最新 schema 版本行；逻辑：无版本表返回 null，否则取版本号最大的记录。
    private VersionRow readVersionRow(Connection connection) throws SQLException {
        if (!tableExists(connection, "schema_version")) {
            return null;
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT version, migration_checksum FROM schema_version ORDER BY version DESC LIMIT 1")) {
            return resultSet.next() ? new VersionRow(resultSet.getInt(1), resultSet.getString(2)) : null;
        }
    }

    // 作用：校验 schema 版本；逻辑：版本高于当前支持值时拒绝打开，避免旧程序写新库。
    private void verifyVersionRow(VersionRow row) {
        if (row == null) {
            return;
        }
        if (row.version() > 1) {
            throw new IllegalStateException("SCHEMA_TOO_NEW");
        }
    }

    private record VersionRow(int version, String checksum) {
    }

    // 作用：计算 schema 校验和；逻辑：读取建表脚本并做 SHA-256 摘要。
    private String schemaChecksum() {
        return Ids.sha256(readSchema());
    }

    // 作用：执行建表脚本；逻辑：按分号切分 SQL 并逐条执行非空语句。
    private void applySchema(Statement statement) throws SQLException {
        for (String sql : readSchema().split(";")) {
            if (!sql.isBlank()) {
                statement.execute(sql);
            }
        }
    }

    /**
     * V001 evolves CHECK constraints and adds columns, which SQLite cannot do
     * in place. Rebuild only affected tables and copy the columns that exist in
     * both definitions. {@code legacy_alter_table} is deliberately enabled
     * while renaming: otherwise SQLite rewrites foreign keys in other tables to
     * point at the temporary legacy table, which makes a partially applied
     * migration unrecoverable.
     */
    // 作用：重建结构变化的表；逻辑：关闭外键、保留旧改名表，逐表重建并回填数据后恢复设置。
    private void migrateChangedTables(Connection connection, Statement statement) throws SQLException {
        Set<String> tables = changedTables(connection);
        if (tables.isEmpty()) {
            return;
        }
        boolean foreignKeys = foreignKeysEnabled(statement);
        try {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.execute("PRAGMA legacy_alter_table = ON");
            for (String table : tables) {
                rebuildTable(connection, statement, table);
            }
        } finally {
            statement.execute("PRAGMA legacy_alter_table = OFF");
            statement.execute("PRAGMA foreign_keys = " + (foreignKeys ? "ON" : "OFF"));
        }
    }

    // 作用：识别需要重建的表；逻辑：检查关键列与取值标记是否缺失，汇总受影响表集合。
    private Set<String> changedTables(Connection connection) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        if (!definitionContains(connection, "value_data_edge", "COPY_ALL")
                || !definitionContains(connection, "value_data_edge", "BUILDER_RESULT")
                || !definitionContains(connection, "value_data_edge", "SQL_PARAMETER")
                || !definitionContains(connection, "value_data_edge", "MUTATES_PARAMETER")
                || !definitionContains(connection, "value_data_edge", "RUNTIME_OBSERVED")
                || !definitionContains(connection, "value_data_edge", "provenance")) {
            tables.add("value_data_edge");
        }
        if (!definitionContains(connection, "value_node", "LAMBDA_PARAMETER")
                || !definitionContains(connection, "value_node", "EXTERNAL_BOUNDARY")
                || !definitionContains(connection, "value_node", "origin_file")) {
            tables.add("value_node");
        }
        if (!definitionContains(connection, "parsed_invoke_edge", "VIRTUAL_UNIQUE")) {
            tables.add("parsed_invoke_edge");
        }
        if (!definitionContains(connection, "pending_parse_invoke_ref", "dispatch_kind")) {
            tables.add("pending_parse_invoke_ref");
        }
        if (!definitionContains(connection, "method_summary", "parameter_mutations_json")) {
            tables.add("method_summary");
        }
        if (definitionContains(connection, "edge_evidence", "logical_edge_key")
                && !definitionContains(connection, "edge_evidence", "scope_symbol_id")) {
            tables.add("edge_evidence");
        }
        if (!definitionContains(connection, "source_code_symbol", "INTERFACE")
                || !definitionContains(connection, "source_code_symbol", "symbol_role")) {
            tables.add("source_code_symbol");
        }
        for (String table : SYMBOL_DEPENDENT_TABLES) {
            if (definitionContains(connection, table, "code_symbol_legacy")
                    || definitionContains(connection, table, "source_code_symbol_legacy")) {
                tables.add(table);
            }
        }
        return tables;
    }

    // 作用：判断表定义是否包含标记；逻辑：取建表 SQL 后做子串匹配。
    private boolean definitionContains(Connection connection, String table, String token) throws SQLException {
        String definition = tableDefinition(connection, table);
        return definition != null && definition.contains(token);
    }

    // 作用：重建单张表；逻辑：改名为临时表，按新定义建表，回填共有列后删除临时表。
    private void rebuildTable(Connection connection, Statement statement, String table) throws SQLException {
        String definition = tableDefinition(connection, table);
        if (definition == null) {
            return;
        }
        List<String> oldColumns = columns(connection, table);
        List<String> newColumns = columnsFromDefinition(table);
        List<String> sharedColumns = new ArrayList<>(oldColumns);
        sharedColumns.retainAll(newColumns);
        if (sharedColumns.isEmpty()) {
            throw new IllegalStateException("Cannot migrate " + table + ": no shared columns");
        }
        String legacyTable = "ftg_legacy_" + table;
        statement.execute("DROP TABLE IF EXISTS " + legacyTable);
        statement.execute("ALTER TABLE " + table + " RENAME TO " + legacyTable);
        statement.execute(tableDefinitionFromSchema(table).replace("IF NOT EXISTS ", ""));
        String columnList = String.join(", ", sharedColumns);
        statement.execute("INSERT INTO " + table + "(" + columnList + ") SELECT " + columnList
                + " FROM " + legacyTable);
        statement.execute("DROP TABLE " + legacyTable);
    }

    // 作用：读取表建表语句；逻辑：从 sqlite_master 按表名取 SQL，缺失返回 null。
    private String tableDefinition(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    // 作用：读取现有列名；逻辑：执行 PRAGMA table_info 并收集列名。
    private List<String> columns(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            List<String> columns = new ArrayList<>();
            while (resultSet.next()) {
                columns.add(resultSet.getString("name"));
            }
            return columns;
        }
    }

    // 作用：解析新定义中的列名；逻辑：截取括号内内容，跳过约束行并取每行首字段。
    private List<String> columnsFromDefinition(String table) throws SQLException {
        String definition = tableDefinitionFromSchema(table);
        int start = definition.indexOf('(');
        int end = definition.lastIndexOf(')');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("Invalid schema definition for " + table);
        }
        List<String> columns = new ArrayList<>();
        for (String line : definition.substring(start + 1, end).split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("FOREIGN KEY")
                    || trimmed.startsWith("UNIQUE") || trimmed.startsWith("PRIMARY KEY")
                    || trimmed.startsWith("CHECK")) {
                continue;
            }
            int space = trimmed.indexOf(' ');
            if (space > 0) {
                columns.add(trimmed.substring(0, space));
            }
        }
        return columns;
    }

    // 作用：检查外键开关状态；逻辑：读取 PRAGMA foreign_keys 并判断是否为 1。
    private boolean foreignKeysEnabled(Statement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery("PRAGMA foreign_keys")) {
            return resultSet.next() && resultSet.getInt(1) == 1;
        }
    }

    // 作用：迁移后校验外键；逻辑：执行 foreign_key_check，存在违规行时抛出异常。
    private void verifyForeignKeys(Statement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (resultSet.next()) {
                throw new IllegalStateException("FOREIGN_KEY_VIOLATION after schema migration: "
                        + resultSet.getString(1));
            }
        }
    }

    /**
     * Unresolved calls used to be duplicated in both tables. The pending row is
     * the authoritative one, so the call-graph copy is dropped instead of
     * being counted as a call.
     */
    // 作用：清理重复的未解析调用；逻辑：从调用边表删除解析级别为 UNRESOLVED 的行。
    private void moveUnresolvedCallsToPendingRefs(Statement statement) throws SQLException {
        statement.executeUpdate("DELETE FROM parsed_invoke_edge WHERE resolution = 'UNRESOLVED'");
    }

    // 作用：从建表脚本提取单表定义；逻辑：定位建表标记后截取到分号，缺失时抛出异常。
    private String tableDefinitionFromSchema(String table) {
        String marker = "CREATE TABLE IF NOT EXISTS " + table;
        int start = readSchema().indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Schema table definition is missing: " + table);
        }
        int end = readSchema().indexOf(';', start);
        return readSchema().substring(start, end);
    }

    // 作用：读取内置建表脚本；逻辑：从类路径加载迁移 SQL 并按 UTF-8 解码。
    private String readSchema() {
        try (InputStream input = getClass().getResourceAsStream("/db/migration/V001__init.sql")) {
            if (input == null) {
                throw new IllegalStateException("Schema resource is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read schema resource", e);
        }
    }
}
