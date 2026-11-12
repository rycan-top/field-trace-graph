package io.fieldtracegraph.storage.sqlite;

import io.fieldtracegraph.core.model.AnalysisResult;
import io.fieldtracegraph.core.model.CallEdgeRecord;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.EdgeEvidence;
import io.fieldtracegraph.core.model.IndexStatusResult;
import io.fieldtracegraph.core.model.IndexQuality;
import io.fieldtracegraph.core.model.MethodCandidate;
import io.fieldtracegraph.core.model.PendingRefRecord;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.port.IndexRepository;
import io.fieldtracegraph.storage.sqlite.dao.EdgeEvidenceDao;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：索引仓库的总实现，串起各 DAO、发布器与状态读取器。
// 使用场景：CLI 每次打开项目索引时创建。

public final class SqliteRepository implements IndexRepository {
    private final Connection connection;
    private final SqliteSchemaMigrator schemaMigrator;
    private final SqliteIndexPublisher indexPublisher;
    private final SqliteIndexAttemptRecorder attemptRecorder;
    private final SqliteGraphReadSession snapshotReader;
    private final SqliteIndexStateReader stateReader;
    private final EdgeEvidenceDao edgeEvidenceDao;

    // 作用：创建索引仓库；逻辑：按读写模式建连，并初始化迁移器、发布器与各读取组件。
    SqliteRepository(SqliteDataSourceFactory connectionFactory, Path databasePath, boolean readOnly) {
        this.connection = connectionFactory.open(databasePath, readOnly);
        this.schemaMigrator = new SqliteSchemaMigrator();
        this.indexPublisher = new SqliteIndexPublisher(connection);
        this.attemptRecorder = new SqliteIndexAttemptRecorder(connection);
        this.snapshotReader = new SqliteGraphReadSession(connection);
        this.stateReader = new SqliteIndexStateReader(connection);
        this.edgeEvidenceDao = new EdgeEvidenceDao(connection);
    }

    // 作用：初始化存储结构；逻辑：委托 schema 迁移器建表并升级版本。
    @Override
    public void initialize() {
        schemaMigrator.migrate(connection);
    }

    // 作用：检查 schema 是否就绪；逻辑：委托状态读取器判断版本表。
    @Override
    public boolean isSchemaInitialized() {
        return stateReader.isSchemaInitialized();
    }

    // 作用：关闭仓库；逻辑：关闭底层数据库连接。
    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to close SQLite connection", e);
        }
    }

    // 作用：判断索引是否可用；逻辑：委托状态读取器校验 schema 与图状态。
    @Override
    public boolean isReady() {
        return stateReader.isReady();
    }

    // 作用：读取索引代数；逻辑：委托状态读取器查询 active_generation。
    @Override
    public int generation() {
        return stateReader.generation();
    }

    // 作用：读取图状态；逻辑：委托状态读取器查询 graph_status。
    @Override
    public String graphStatus() {
        return stateReader.graphStatus();
    }

    // 作用：读取上下文指纹；逻辑：委托状态读取器查询 context_hash。
    @Override
    public String contextHash() {
        return stateReader.contextHash();
    }

    // 作用：读取项目根指纹；逻辑：委托状态读取器查询项目路径指纹。
    @Override
    public String projectRootFingerprint() {
        return stateReader.projectRootFingerprint();
    }

    // 作用：发布索引结果；逻辑：委托发布器在事务内写入新 generation。
    @Override
    public void publish(
            AnalysisResult analysis,
            Path projectRoot,
            String contextHash,
            String parserVersion,
            String attemptId,
            Set<String> publishPaths
    ) {
        indexPublisher.publish(analysis, projectRoot, contextHash, parserVersion, attemptId, publishPaths);
    }

    // 作用：登记索引尝试；逻辑：委托尝试记录器写入基线、文件与删除集合。
    @Override
    public void beginAttempt(
            String attemptId,
            int baseGeneration,
            List<SourceFileRecord> files,
            Map<String, String> previousHashes,
            Set<String> deletedPaths
    ) {
        attemptRecorder.begin(attemptId, baseGeneration, files, previousHashes, deletedPaths);
    }

    // 作用：标记索引失败；逻辑：委托尝试记录器写入错误码与信息。
    @Override
    public void markFailedAttempt(String attemptId, String errorCode, String errorMessage) {
        attemptRecorder.markFailed(attemptId, errorCode, errorMessage);
    }

    // 作用：搜索方法候选；逻辑：委托快照读取器执行分页查询。
    @Override
    public List<MethodCandidate> findMethods(String name, int offset, int limit, boolean includeAccessors) {
        return snapshotReader.findMethods(name, offset, limit, includeAccessors);
    }

    // 作用：按 ID 取方法；逻辑：委托快照读取器精确查询。
    @Override
    public MethodCandidate findMethodById(String methodId) {
        return snapshotReader.findMethodById(methodId);
    }

    // 作用：按选择器解析方法；逻辑：委托快照读取器处理歧义与签名匹配。
    @Override
    public MethodCandidate findMethodBySelector(String selector) {
        return snapshotReader.findMethodBySelector(selector);
    }

    // 作用：统计方法数量；逻辑：委托快照读取器按名称与访问器规则计数。
    @Override
    public int countMethods(String name, boolean includeAccessors) {
        return snapshotReader.countMethods(name, includeAccessors);
    }

    // 作用：列出方法目标节点；逻辑：委托快照读取器按作用域取节点。
    @Override
    public List<ValueNodeRecord> findTargets(String methodId) {
        return snapshotReader.findTargets(methodId);
    }

    // 作用：加载作用域内节点；逻辑：委托快照读取器按方法 ID 查询。
    @Override
    public List<ValueNodeRecord> nodesForScope(String methodId) {
        return snapshotReader.nodesForScope(methodId);
    }

    // 作用：批量加载值节点；逻辑：委托快照读取器按作用域 ID 查询。
    @Override
    public List<ValueNodeRecord> valueNodesForScopeIds(List<String> scopeIds) {
        return snapshotReader.valueNodesForScopeIds(scopeIds);
    }

    // 作用：批量加载数据边；逻辑：委托快照读取器按作用域 ID 查询。
    @Override
    public List<DataEdgeRecord> dataEdgesForScopeIds(List<String> scopeIds) {
        return snapshotReader.dataEdgesForScopeIds(scopeIds);
    }

    // 作用：批量加载边证据；逻辑：委托快照读取器按作用域 ID 查询。
    @Override
    public List<EdgeEvidence> edgeEvidenceForScopeIds(List<String> scopeIds) {
        return snapshotReader.edgeEvidenceForScopeIds(scopeIds);
    }

    // 作用：加载调用边；逻辑：委托快照读取器合并已解析与未解析调用。
    @Override
    public List<CallEdgeRecord> callEdgesForScopeIds(List<String> callerSymbolIds) {
        return snapshotReader.callEdgesForScopeIds(callerSymbolIds);
    }

    // 作用：加载未解析引用；逻辑：委托快照读取器按调用方查询。
    @Override
    public List<PendingRefRecord> pendingRefsForScopeIds(List<String> callerSymbolIds) {
        return snapshotReader.pendingRefsForScopeIds(callerSymbolIds);
    }

    // 作用：读取源文件文本；逻辑：委托快照读取器按文件 ID 取内容字段。
    @Override
    public String sourceFileText(String fileId) {
        return snapshotReader.sourceFileText(fileId);
    }

    // 作用：按 ID 读源文件；逻辑：委托快照读取器查询元数据。
    @Override
    public SourceFileRecord sourceFileByFileId(String fileId) {
        return snapshotReader.sourceFileByFileId(fileId);
    }

    // 作用：批量读源文件；逻辑：委托快照读取器按 ID 列表查询。
    @Override
    public Map<String, SourceFileRecord> sourceFilesByIds(List<String> fileIds) {
        return snapshotReader.sourceFilesByIds(fileIds);
    }

    // 作用：统计源文件数；逻辑：委托快照读取器计数。
    @Override
    public int sourceFileCount() {
        return snapshotReader.sourceFileCount();
    }

    // 作用：读取文件哈希；逻辑：委托快照读取器扫描路径与哈希。
    @Override
    public Map<String, String> sourceFileHashes() {
        return snapshotReader.sourceFileHashes();
    }

    // 作用：由路径反查符号；逻辑：委托快照读取器按相对路径集合查询。
    @Override
    public Set<String> symbolIdsByRelativePaths(Set<String> relativePaths) {
        return snapshotReader.symbolIdsByRelativePaths(relativePaths);
    }

    // 作用：构建被调方到调用方映射；逻辑：委托快照读取器聚合调用关系。
    @Override
    public Map<String, Set<String>> callersByCallee() {
        return snapshotReader.callersByCallee();
    }

    // 作用：构建引用名到调用方映射；逻辑：委托快照读取器聚合未解析引用。
    @Override
    public Map<String, Set<String>> pendingRefCallersByReferenceName() {
        return snapshotReader.pendingRefCallersByReferenceName();
    }

    // 作用：由路径查限定名；逻辑：委托快照读取器批量查询。
    @Override
    public Set<String> qnamesByRelativePaths(Set<String> relativePaths) {
        return snapshotReader.qnamesByRelativePaths(relativePaths);
    }

    // 作用：由符号查路径；逻辑：委托快照读取器批量查询并去重。
    @Override
    public Set<String> relativePathsBySymbolIds(Set<String> symbolIds) {
        return snapshotReader.relativePathsBySymbolIds(symbolIds);
    }

    // 作用：统计符号数；逻辑：委托快照读取器计数。
    @Override
    public int symbolCount() {
        return snapshotReader.symbolCount();
    }

    // 作用：统计值节点数；逻辑：委托快照读取器计数。
    @Override
    public int valueNodeCount() {
        return snapshotReader.valueNodeCount();
    }

    // 作用：统计数据边数；逻辑：委托快照读取器计数。
    @Override
    public int dataEdgeCount() {
        return snapshotReader.dataEdgeCount();
    }

    // 作用：组装状态结果；逻辑：委托状态读取器附加新鲜度描述。
    @Override
    public IndexStatusResult toStatusResult(String freshness) {
        return stateReader.toStatusResult(freshness);
    }

    // 作用：统计索引质量；逻辑：委托状态读取器计算比例与边界分布。
    @Override
    public IndexQuality qualityStatistics() {
        return stateReader.qualityStatistics();
    }

    // 作用：导入边证据；逻辑：委托证据 DAO 批量写入并返回条数。
    @Override
    public int importEdgeEvidence(List<EdgeEvidence> evidence) {
        return edgeEvidenceDao.insert(evidence);
    }

    // 作用：判断逻辑边是否存在；逻辑：委托证据 DAO 按逻辑边键校验。
    @Override
    public boolean hasLogicalEdge(String logicalEdgeKey) {
        return edgeEvidenceDao.logicalEdgeExists(logicalEdgeKey);
    }
}
