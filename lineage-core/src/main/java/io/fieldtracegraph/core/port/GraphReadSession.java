package io.fieldtracegraph.core.port;

import io.fieldtracegraph.core.model.CallEdgeRecord;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.EdgeEvidence;
import io.fieldtracegraph.core.model.MethodCandidate;
import io.fieldtracegraph.core.model.MethodSummaryRecord;
import io.fieldtracegraph.core.model.PendingRefRecord;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;

import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：快照内查询会话端口。
// 使用场景：一次查询内复用连接与缓存，减少重复加载。

/**
 * One read-only snapshot connection for a single query. It reads the same
 * published generation for every call and must be closed after use.
 */
public interface GraphReadSession extends AutoCloseable {
    // 作用：读取本次快照的索引代数；逻辑：返回打开会话时固定的 generation 编号。
    int generation();

    // 作用：读取图状态；逻辑：返回发布时记录的 READY、PARTIAL 等状态标识。
    String graphStatus();

    // 作用：读取索引上下文指纹；逻辑：返回构建当前代图时的环境与配置哈希。
    String contextHash();

    // 作用：按方法名检索方法候选；逻辑：带偏移、上限与访问器开关查询方法索引。
    List<MethodCandidate> findMethods(String name, int offset, int limit, boolean includeAccessors);

    // 作用：按方法 ID 精确取方法；逻辑：返回唯一候选，缺失时返回 null。
    MethodCandidate findMethodById(String methodId);

    // 作用：按选择器解析方法；逻辑：用类名与方法名组合定位唯一方法。
    MethodCandidate findMethodBySelector(String selector);

    // 作用：统计同名方法数量；逻辑：按名称与访问器开关计算匹配总数。
    int countMethods(String name, boolean includeAccessors);

    // 作用：列出方法的字段目标节点；逻辑：返回该方法可追踪的写入目标集合。
    List<ValueNodeRecord> findTargets(String methodId);

    // 作用：加载方法作用域内的值节点；逻辑：先解析作用域 ID 再批量取节点。
    List<ValueNodeRecord> nodesForScope(String methodId);

    // 作用：按作用域 ID 批量取值节点；逻辑：一次查询返回多个方法共享的节点集合。
    List<ValueNodeRecord> valueNodesForScopeIds(List<String> scopeIds);

    // 作用：按作用域 ID 批量取数据边；逻辑：返回字段级赋值与传递关系。
    List<DataEdgeRecord> dataEdgesForScopeIds(List<String> scopeIds);

    // 作用：按作用域 ID 批量取边证据；逻辑：返回每条逻辑边对应的来源与解析级别。
    List<EdgeEvidence> edgeEvidenceForScopeIds(List<String> scopeIds);

    // 作用：按调用方批量取调用边；逻辑：返回调用方到被调方的方法调用关系。
    List<CallEdgeRecord> callEdgesForScopeIds(List<String> callerSymbolIds);

    // 作用：按作用域 ID 批量取方法摘要；逻辑：返回参数、返回值与目标字段的概要信息。
    List<MethodSummaryRecord> methodSummariesForScopeIds(List<String> scopeIds);

    // 作用：按调用方批量取未解析引用；逻辑：返回等待后续索引补齐的符号引用。
    List<PendingRefRecord> pendingRefsForScopeIds(List<String> callerSymbolIds);

    // 作用：读取源文件内容；逻辑：按文件 ID 返回快照中保存的源码文本。
    String sourceFileText(String fileId);

    // 作用：读取源文件元数据；逻辑：按文件 ID 返回路径、哈希与大小信息。
    SourceFileRecord sourceFileByFileId(String fileId);

    // 作用：批量读取源文件元数据；逻辑：按 ID 列表返回 ID 到记录的映射。
    Map<String, SourceFileRecord> sourceFilesByIds(List<String> fileIds);

    // 作用：读取全部源文件哈希；逻辑：返回路径到内容哈希的映射用于增量比对。
    Map<String, String> sourceFileHashes();

    // 作用：由相对路径反查符号 ID；逻辑：把文件路径集合映射为其中定义的符号集合。
    Set<String> symbolIdsByRelativePaths(Set<String> relativePaths);

    // 作用：构建被调方到调用方的反向索引；逻辑：聚合全部调用边供影响分析使用。
    Map<String, Set<String>> callersByCallee();

    // 作用：构建未解析引用的反向索引；逻辑：按引用名聚合等待解析的调用方。
    Map<String, Set<String>> pendingRefCallersByReferenceName();

    // 作用：由相对路径取全限定名；逻辑：返回文件内声明类型的限定名集合。
    Set<String> qnamesByRelativePaths(Set<String> relativePaths);

    // 作用：由符号 ID 取全限定名；逻辑：批量返回符号到限定名的映射。
    Map<String, String> qnamesBySymbolIds(List<String> symbolIds);

    // 作用：由符号 ID 反查所属文件路径；逻辑：返回符号所在源文件的相对路径集合。
    Set<String> relativePathsBySymbolIds(Set<String> symbolIds);

    // 作用：统计快照内源文件数；逻辑：返回当前 generation 的文件总数。
    int sourceFileCount();

    // 作用：统计符号数量；逻辑：返回方法、类型等符号的总数。
    int symbolCount();

    // 作用：统计值节点数量；逻辑：返回值级图节点的总数。
    int valueNodeCount();

    // 作用：统计数据边数量；逻辑：返回字段级关系边的总数。
    int dataEdgeCount();

    // 作用：关闭查询会话；逻辑：释放只读连接与事务资源。
    @Override
    void close();
}
