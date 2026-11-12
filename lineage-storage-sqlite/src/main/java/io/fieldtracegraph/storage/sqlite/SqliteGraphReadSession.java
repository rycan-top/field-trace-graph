package io.fieldtracegraph.storage.sqlite;

import io.fieldtracegraph.core.model.CallEdgeRecord;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.EdgeEvidence;
import io.fieldtracegraph.core.model.MethodCandidate;
import io.fieldtracegraph.core.model.MethodSummaryRecord;
import io.fieldtracegraph.core.model.PendingRefRecord;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.port.GraphReadSession;
import io.fieldtracegraph.storage.sqlite.dao.CallEdgeDao;
import io.fieldtracegraph.storage.sqlite.dao.EdgeEvidenceDao;
import io.fieldtracegraph.storage.sqlite.dao.MethodSummaryDao;
import io.fieldtracegraph.storage.sqlite.dao.SourceFileDao;
import io.fieldtracegraph.storage.sqlite.dao.SymbolDao;
import io.fieldtracegraph.storage.sqlite.dao.ValueGraphDao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：在只读快照内执行查询。
// 使用场景：一次 trace 内复用连接与缓存。

/**
 * SQLite implementation of a single read-only snapshot session. All reads use
 * the same connection so forward and backward traversals observe one
 * generation.
 */
public final class SqliteGraphReadSession implements GraphReadSession {
    private final Connection connection;
    private final SourceFileDao sourceFileDao;
    private final SymbolDao symbolDao;
    private final CallEdgeDao callEdgeDao;
    private final EdgeEvidenceDao edgeEvidenceDao;
    private final MethodSummaryDao methodSummaryDao;
    private final ValueGraphDao valueGraphDao;
    private boolean closed;

    // 作用：创建只读查询会话；逻辑：保存连接并为各数据表初始化对应 DAO。
    public SqliteGraphReadSession(Connection connection) {
        this.connection = connection;
        this.sourceFileDao = new SourceFileDao(connection);
        this.symbolDao = new SymbolDao(connection);
        this.callEdgeDao = new CallEdgeDao(connection);
        this.edgeEvidenceDao = new EdgeEvidenceDao(connection);
        this.methodSummaryDao = new MethodSummaryDao(connection);
        this.valueGraphDao = new ValueGraphDao(connection);
    }

    // 作用：读取当前索引代数；逻辑：从索引状态单例行取 active_generation，缺失时返回 0。
    @Override
    public int generation() {
        return scalarInt("SELECT active_generation FROM full_index_state WHERE singleton_id = 1", 0);
    }

    // 作用：读取图状态；逻辑：从索引状态单例行取 graph_status，缺失时返回 EMPTY。
    @Override
    public String graphStatus() {
        return scalarText("SELECT graph_status FROM full_index_state WHERE singleton_id = 1", "EMPTY");
    }

    // 作用：读取上下文指纹；逻辑：从索引状态单例行取 context_hash。
    @Override
    public String contextHash() {
        return scalarText("SELECT context_hash FROM full_index_state WHERE singleton_id = 1", null);
    }

    // 作用：搜索方法候选；逻辑：委托符号 DAO 执行分页查询。
    @Override
    public List<MethodCandidate> findMethods(String name, int offset, int limit, boolean includeAccessors) {
        return symbolDao.findMethods(name, offset, limit, includeAccessors);
    }

    // 作用：按 ID 取方法；逻辑：委托符号 DAO 精确查询。
    @Override
    public MethodCandidate findMethodById(String methodId) {
        return symbolDao.findMethodById(methodId);
    }

    // 作用：按选择器解析方法；逻辑：委托符号 DAO 处理 ID、限定名与歧义。
    @Override
    public MethodCandidate findMethodBySelector(String selector) {
        return symbolDao.findMethodBySelector(selector);
    }

    // 作用：统计方法数量；逻辑：委托符号 DAO 按名称与访问器规则计数。
    @Override
    public int countMethods(String name, boolean includeAccessors) {
        return symbolDao.countMethods(name, includeAccessors);
    }

    // 作用：列出方法目标节点；逻辑：复用值图 DAO 的按作用域节点查询。
    @Override
    public List<ValueNodeRecord> findTargets(String methodId) {
        return valueGraphDao.nodesForScope(methodId);
    }

    // 作用：加载作用域内节点；逻辑：委托值图 DAO 按方法 ID 查询。
    @Override
    public List<ValueNodeRecord> nodesForScope(String methodId) {
        return valueGraphDao.nodesForScope(methodId);
    }

    // 作用：批量加载值节点；逻辑：委托值图 DAO 按作用域 ID 查询。
    @Override
    public List<ValueNodeRecord> valueNodesForScopeIds(List<String> scopeIds) {
        return valueGraphDao.valueNodesForScopeIds(scopeIds);
    }

    // 作用：批量加载数据边；逻辑：委托值图 DAO 按作用域 ID 查询。
    @Override
    public List<DataEdgeRecord> dataEdgesForScopeIds(List<String> scopeIds) {
        return valueGraphDao.dataEdgesForScopeIds(scopeIds);
    }

    // 作用：批量加载边证据；逻辑：委托证据 DAO 按作用域 ID 查询。
    @Override
    public List<EdgeEvidence> edgeEvidenceForScopeIds(List<String> scopeIds) {
        return edgeEvidenceDao.forScopeIds(scopeIds);
    }

    // 作用：加载调用边；逻辑：合并已解析边与由未解析引用投影出的调用边。
    @Override
    public List<CallEdgeRecord> callEdgesForScopeIds(List<String> callerSymbolIds) {
        List<CallEdgeRecord> stored = callEdgeDao.callEdgesForScopeIds(callerSymbolIds).stream()
                .filter(edge -> !"UNRESOLVED".equals(edge.resolution()))
                .toList();
        List<CallEdgeRecord> unresolved = callEdgeDao.unresolvedCallEdgesForScopeIds(callerSymbolIds);
        if (unresolved.isEmpty()) {
            return stored;
        }
        List<CallEdgeRecord> result = new ArrayList<>(stored.size() + unresolved.size());
        result.addAll(stored);
        result.addAll(unresolved);
        return List.copyOf(result);
    }

    // 作用：批量加载方法摘要；逻辑：委托摘要 DAO 按作用域 ID 查询。
    @Override
    public List<MethodSummaryRecord> methodSummariesForScopeIds(List<String> scopeIds) {
        return methodSummaryDao.summariesForScopeIds(scopeIds);
    }

    // 作用：批量加载未解析引用；逻辑：委托调用边 DAO 按调用方查询。
    @Override
    public List<PendingRefRecord> pendingRefsForScopeIds(List<String> callerSymbolIds) {
        return callEdgeDao.pendingRefsForScopeIds(callerSymbolIds);
    }

    // 作用：读取源文件文本；逻辑：按文件 ID 取源文件记录中的路径字段。
    @Override
    public String sourceFileText(String fileId) {
        return sourceFileDao.relativePath(fileId);
    }

    // 作用：按 ID 读源文件元数据；逻辑：委托源文件 DAO 查询单条记录。
    @Override
    public SourceFileRecord sourceFileByFileId(String fileId) {
        return sourceFileDao.byId(fileId);
    }

    // 作用：批量读源文件元数据；逻辑：委托源文件 DAO 按 ID 列表查询。
    @Override
    public Map<String, SourceFileRecord> sourceFilesByIds(List<String> fileIds) {
        return sourceFileDao.byIds(fileIds);
    }

    // 作用：读取全部文件哈希；逻辑：委托源文件 DAO 扫描路径与内容哈希。
    @Override
    public Map<String, String> sourceFileHashes() {
        return sourceFileDao.hashes();
    }

    // 作用：由路径反查符号 ID；逻辑：委托符号 DAO 按相对路径集合查询。
    @Override
    public Set<String> symbolIdsByRelativePaths(Set<String> relativePaths) {
        return symbolDao.symbolIdsByRelativePaths(relativePaths);
    }

    // 作用：构建被调方到调用方映射；逻辑：委托调用边 DAO 聚合调用关系。
    @Override
    public Map<String, Set<String>> callersByCallee() {
        return callEdgeDao.callersByCallee();
    }

    // 作用：构建引用名到调用方映射；逻辑：委托调用边 DAO 聚合未解析引用。
    @Override
    public Map<String, Set<String>> pendingRefCallersByReferenceName() {
        return callEdgeDao.pendingRefCallersByReferenceName();
    }

    // 作用：由路径查全限定名；逻辑：委托符号 DAO 按相对路径集合查询。
    @Override
    public Set<String> qnamesByRelativePaths(Set<String> relativePaths) {
        return symbolDao.qnamesByRelativePaths(relativePaths);
    }

    // 作用：由符号 ID 查全限定名；逻辑：委托符号 DAO 批量查询。
    @Override
    public Map<String, String> qnamesBySymbolIds(List<String> symbolIds) {
        return symbolDao.qnamesBySymbolIds(symbolIds);
    }

    // 作用：由符号 ID 查文件路径；逻辑：委托符号 DAO 批量查询并去重。
    @Override
    public Set<String> relativePathsBySymbolIds(Set<String> symbolIds) {
        return symbolDao.relativePathsBySymbolIds(symbolIds);
    }

    // 作用：统计源文件数；逻辑：委托源文件 DAO 计数。
    @Override
    public int sourceFileCount() {
        return sourceFileDao.count();
    }

    // 作用：统计符号数；逻辑：委托符号 DAO 计数。
    @Override
    public int symbolCount() {
        return symbolDao.count();
    }

    // 作用：统计值节点数；逻辑：委托值图 DAO 计数。
    @Override
    public int valueNodeCount() {
        return valueGraphDao.valueNodeCount();
    }

    // 作用：统计数据边数；逻辑：委托值图 DAO 计数。
    @Override
    public int dataEdgeCount() {
        return valueGraphDao.dataEdgeCount();
    }

    // 作用：关闭查询会话；逻辑：幂等关闭底层连接并标记会话已关闭。
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to close SQLite read session", e);
        }
    }

    // 作用：执行单值整数查询；逻辑：运行 SQL 取首行首列，无结果时返回兜底值。
    private int scalarInt(String query, int fallback) {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(query)) {
            return resultSet.next() ? resultSet.getInt(1) : fallback;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read index state", e);
        }
    }

    // 作用：执行单值文本查询；逻辑：运行 SQL 取首行首列，无结果时返回兜底值。
    private String scalarText(String query, String fallback) {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(query)) {
            return resultSet.next() ? resultSet.getString(1) : fallback;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read index state", e);
        }
    }
}
