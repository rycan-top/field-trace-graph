package io.fieldtracegraph.storage.sqlite;

import io.fieldtracegraph.core.model.AnalysisResult;
import io.fieldtracegraph.core.model.CallEdgeRecord;
import io.fieldtracegraph.core.model.Diagnostic;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.EdgeEvidence;
import io.fieldtracegraph.core.model.PendingRefRecord;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.port.IndexPublisher;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.storage.sqlite.dao.CallEdgeDao;
import io.fieldtracegraph.storage.sqlite.dao.EdgeEvidenceDao;
import io.fieldtracegraph.storage.sqlite.dao.MethodSummaryDao;
import io.fieldtracegraph.storage.sqlite.dao.SourceFileDao;
import io.fieldtracegraph.storage.sqlite.dao.SymbolDao;
import io.fieldtracegraph.storage.sqlite.dao.ValueGraphDao;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// 作用：把一个 generation 的符号、边、证据与摘要原子写入数据库。
// 使用场景：index 流程的最后一步，避免查询读到半成品。

/**
 * Publishes one candidate graph in a single SQLite transaction. The DAO layer
 * owns table SQL; this class owns the transaction boundary, state update and
 * rollback semantics.
 */
public final class SqliteIndexPublisher implements IndexPublisher {
    private final Connection connection;
    private final SqliteWriteTransaction transaction;
    private final SourceFileDao sourceFileDao;
    private final SymbolDao symbolDao;
    private final CallEdgeDao callEdgeDao;
    private final ValueGraphDao valueGraphDao;
    private final EdgeEvidenceDao edgeEvidenceDao;
    private final MethodSummaryDao methodSummaryDao;

    // 作用：创建发布器；逻辑：绑定连接并初始化事务包装与各类表 DAO。
    public SqliteIndexPublisher(Connection connection) {
        this.connection = connection;
        this.transaction = new SqliteWriteTransaction(connection);
        this.sourceFileDao = new SourceFileDao(connection);
        this.symbolDao = new SymbolDao(connection);
        this.callEdgeDao = new CallEdgeDao(connection);
        this.valueGraphDao = new ValueGraphDao(connection);
        this.edgeEvidenceDao = new EdgeEvidenceDao(connection);
        this.methodSummaryDao = new MethodSummaryDao(connection);
    }

    // 作用：原子发布一次索引；逻辑：开事务后全量或增量写入，更新状态并提交，异常回滚。
    @Override
    public void publish(
            AnalysisResult analysis,
            Path projectRoot,
            String contextHash,
            String parserVersion,
            String attemptId,
            Set<String> publishPaths
    ) {
        try {
            transaction.begin();
            try {
                if (publishPaths == null) {
                    clearGraphTables();
                    insertAll(analysis);
                } else {
                    publishIncremental(analysis, publishPaths);
                }
                updateIndexState(projectRoot, contextHash, parserVersion, analysis.diagnostics(), attemptId);
                markAttemptFilesAnalyzed(attemptId, analysis.sourceFiles());
                transaction.commit();
            } catch (SQLException e) {
                throw new IllegalStateException("Unable to publish index: " + e.getMessage(), e);
            }
        } catch (RuntimeException e) {
            transaction.rollback();
            throw e;
        } finally {
            transaction.resetAutoCommit();
        }
    }

    // 作用：全量写入分析结果；逻辑：清空后依次写入文件、符号、节点、边、证据、调用与摘要。
    private void insertAll(AnalysisResult analysis) {
        sourceFileDao.insert(analysis.sourceFiles());
        symbolDao.insert(analysis.symbols());
        valueGraphDao.insertValueNodes(analysis.valueNodes());
        valueGraphDao.insertDataEdges(analysis.dataEdges());
        insertSourceEvidence(analysis.dataEdges());
        Set<String> symbolIds = symbolDao.allSymbolIds();
        PublishableCalls calls = publishableCalls(analysis.callEdges(), symbolIds);
        callEdgeDao.insertCallEdges(calls.edges());
        callEdgeDao.insertPendingRefs(mergePendingRefs(analysis.pendingRefs(), calls.pendingRefs()));
        methodSummaryDao.insert(analysis.methodSummaries());
    }

    // 作用：增量写入分析结果；逻辑：先删本次路径旧数据，再只写入这些文件产出的图数据。
    private void publishIncremental(AnalysisResult analysis, Set<String> publishPaths) throws SQLException {
        deleteSourceFiles(publishPaths);
        List<SourceFileRecord> publishedFiles = analysis.sourceFiles().stream()
                .filter(file -> publishPaths.contains(file.relativePath()))
                .toList();
        Set<String> publishedFileIds = publishedFiles.stream()
                .map(SourceFileRecord::fileId)
                .collect(Collectors.toCollection(HashSet::new));
        sourceFileDao.insert(publishedFiles);
        symbolDao.insert(analysis.symbols().stream()
                .filter(symbol -> publishedFileIds.contains(symbol.fileId()))
                .toList());
        valueGraphDao.insertValueNodes(analysis.valueNodes().stream()
                .filter(node -> publishedFileIds.contains(node.fileId()))
                .toList());
        List<DataEdgeRecord> publishedEdges = analysis.dataEdges().stream()
                .filter(edge -> publishedFileIds.contains(edge.fileId()))
                .toList();
        valueGraphDao.insertDataEdges(publishedEdges);
        insertSourceEvidence(publishedEdges);
        PublishableCalls calls = publishableCalls(
                analysis.callEdges().stream()
                        .filter(edge -> publishedFileIds.contains(edge.fileId()))
                        .toList(),
                symbolDao.allSymbolIds()
        );
        callEdgeDao.insertCallEdges(calls.edges());
        callEdgeDao.insertPendingRefs(mergePendingRefs(
                analysis.pendingRefs().stream()
                        .filter(ref -> publishedFileIds.contains(ref.fileId()))
                        .toList(),
                calls.pendingRefs()
        ));
        methodSummaryDao.insert(analysis.methodSummaries().stream()
                .filter(summary -> publishedFileIds.contains(summary.fileId()))
                .toList());
    }

    /**
     * {@code parsed_invoke_edge} means "this call reached a callee". Unresolved
     * calls keep their fact in {@code pending_parse_invoke_ref} only, so the two tables
     * stay disjoint and their counts are directly comparable with tools that
     * separate pending references.
     */
    // 作用：拆分可发布调用；逻辑：跳过未解析与调用方缺失的边，被调方缺失时转为待解析引用。
    private PublishableCalls publishableCalls(
            List<CallEdgeRecord> edges,
            Set<String> knownSymbolIds
    ) {
        List<CallEdgeRecord> publishable = new java.util.ArrayList<>();
        List<PendingRefRecord> pending = new java.util.ArrayList<>();
        for (CallEdgeRecord edge : edges) {
            if ("UNRESOLVED".equals(edge.resolution())) {
                continue;
            }
            if (edge.callerSymbolId() == null
                    || !knownSymbolIds.contains(edge.callerSymbolId())) {
                // A pending row also has a caller foreign key, so there is no
                // safe row to publish when the caller itself is missing.
                continue;
            }
            if (edge.calleeSymbolId() != null
                    && !knownSymbolIds.contains(edge.calleeSymbolId())) {
                pending.add(missingCalleePending(edge));
                continue;
            }
            publishable.add(edge);
        }
        return new PublishableCalls(List.copyOf(publishable), List.copyOf(pending));
    }

    // 作用：把缺失被调方的调用转成待解析引用；逻辑：生成稳定 ID 并保留调用位置与参数信息。
    private PendingRefRecord missingCalleePending(CallEdgeRecord edge) {
        return new PendingRefRecord(
                Ids.sha256(edge.edgeId(), "missing-callee", edge.calleeSymbolId()),
                edge.callSiteId(),
                edge.callerSymbolId(),
                "CONSTRUCTOR".equals(edge.dispatchKind()) ? "CONSTRUCTOR" : "METHOD",
                edge.targetQname(),
                List.of(),
                "CALLEE_SYMBOL_MISSING",
                edge.fileId(),
                edge.lineStart(),
                edge.columnStart(),
                edge.lineEnd(),
                edge.columnEnd(),
                edge.dispatchKind(),
                edge.receiverValueId(),
                edge.argumentValueIds(),
                edge.resultValueId()
        );
    }

    // 作用：合并两批待解析引用；逻辑：按引用 ID 去重并保留先出现的记录。
    private List<PendingRefRecord> mergePendingRefs(
            List<PendingRefRecord> existing,
            List<PendingRefRecord> additional
    ) {
        if (additional.isEmpty()) {
            return existing;
        }
        java.util.LinkedHashMap<String, PendingRefRecord> merged = new java.util.LinkedHashMap<>();
        for (PendingRefRecord ref : existing) {
            merged.put(ref.pendingId(), ref);
        }
        for (PendingRefRecord ref : additional) {
            merged.putIfAbsent(ref.pendingId(), ref);
        }
        return List.copyOf(merged.values());
    }

    // 作用：删除待重算的源文件；逻辑：按相对路径集合批量删除源文件行。
    private void deleteSourceFiles(Set<String> publishPaths) throws SQLException {
        if (publishPaths.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(publishPaths.size(), "?"));
        String sql = "DELETE FROM source_file WHERE relative_path IN (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (String path : publishPaths) {
                statement.setString(index++, path);
            }
            statement.executeUpdate();
        }
    }

    // 作用：写入源码级边证据；逻辑：把数据边转换为源码来源证据后批量插入。
    private void insertSourceEvidence(List<DataEdgeRecord> edges) {
        edgeEvidenceDao.insert(edges.stream()
                .map(EdgeEvidence::source)
                .toList());
    }

    // 作用：清空图数据表；逻辑：按依赖顺序删除摘要、引用、调用、证据、边、节点、符号与文件。
    private void clearGraphTables() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM method_summary");
            statement.executeUpdate("DELETE FROM pending_parse_invoke_ref");
            statement.executeUpdate("DELETE FROM parsed_invoke_edge");
            statement.executeUpdate("DELETE FROM edge_evidence");
            statement.executeUpdate("DELETE FROM value_data_edge");
            statement.executeUpdate("DELETE FROM value_node");
            statement.executeUpdate("DELETE FROM source_code_symbol");
            statement.executeUpdate("DELETE FROM source_file");
        }
    }

    // 作用：更新索引状态；逻辑：按诊断结论确定 READY 或 PARTIAL，递增代数并记录发布信息。
    private void updateIndexState(
            Path projectRoot,
            String contextHash,
            String parserVersion,
            List<Diagnostic> diagnostics,
            String attemptId
    ) throws SQLException {
        List<Diagnostic> statusDiagnostics = diagnostics.stream()
                .filter(this::determinesGraphStatus)
                .toList();
        String status = statusDiagnostics.isEmpty() ? "READY" : "PARTIAL";
        String sql = """
                UPDATE full_index_state
                SET active_generation = active_generation + 1,
                    graph_status = ?,
                    published_at = ?,
                    context_hash = ?,
                    parser_version = ?,
                    project_root_fingerprint = ?,
                    last_attempt_id = ?,
                    last_attempt_base_generation = active_generation,
                    last_attempt_status = ?,
                    last_attempt_started_at = ?,
                    last_attempt_finished_at = ?,
                    last_error_code = ?,
                    last_error_message = ?
                WHERE singleton_id = 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, Instant.now().toString());
            statement.setString(3, contextHash);
            statement.setString(4, parserVersion);
            statement.setString(5, Ids.sha256(projectRoot.toString()));
            statement.setString(6, attemptId);
            statement.setString(7, statusDiagnostics.isEmpty() ? "SUCCEEDED" : "PARTIAL");
            statement.setString(8, Instant.now().toString());
            statement.setString(9, Instant.now().toString());
            statement.setString(10, statusDiagnostics.isEmpty() ? null : statusDiagnostics.get(0).code());
            statement.setString(11, statusDiagnostics.isEmpty() ? null : statusDiagnostics.get(0).message());
            statement.executeUpdate();
        }
    }

    // 作用：判断诊断是否影响图状态；逻辑：阻塞诊断或编译、语义、解码失败时返回 true。
    private boolean determinesGraphStatus(Diagnostic diagnostic) {
        return diagnostic.blocking()
                || "COMPILER_UNAVAILABLE".equals(diagnostic.code())
                || "SEMANTIC_FAILED".equals(diagnostic.code())
                || "DECODE_FAILED".equals(diagnostic.code());
    }

    // 作用：标记尝试文件已分析；逻辑：按尝试 ID 与路径批量更新状态与完成时间。
    private void markAttemptFilesAnalyzed(String attemptId, List<SourceFileRecord> files) throws SQLException {
        String sql = "UPDATE index_file_state SET status = 'ANALYZED', finished_at = ? WHERE attempt_id = ? AND relative_path = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (SourceFileRecord file : files) {
                statement.setString(1, Instant.now().toString());
                statement.setString(2, attemptId);
                statement.setString(3, file.relativePath());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private record PublishableCalls(
            List<CallEdgeRecord> edges,
            List<PendingRefRecord> pendingRefs
    ) {
    }

}
