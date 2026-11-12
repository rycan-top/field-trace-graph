package io.fieldtracegraph.core.port;

import io.fieldtracegraph.core.model.IndexStatusResult;
import io.fieldtracegraph.core.model.IndexQuality;

// 作用：索引状态读取端口。
// 使用场景：status 命令、质量统计与增量索引的前置判断。

public interface IndexStateReader {
    // 作用：判断索引是否可用；逻辑：校验 schema、generation 与图状态是否满足查询条件。
    boolean isReady();

    // 作用：检查 schema 是否就绪；逻辑：查询版本表判断是否已完成初始化。
    boolean isSchemaInitialized();

    // 作用：读取当前索引代数；逻辑：返回已发布的 generation 编号。
    int generation();

    // 作用：读取图状态；逻辑：返回 READY、PARTIAL 等发布状态。
    String graphStatus();

    // 作用：读取上下文指纹；逻辑：返回构建当前图时的环境与配置哈希。
    String contextHash();

    // 作用：读取项目根指纹；逻辑：返回用于识别同一项目的路径指纹。
    String projectRootFingerprint();

    // 作用：组装对外状态视图；逻辑：把内部状态字段转换为状态结果对象。
    IndexStatusResult toStatusResult(String freshness);

    // 作用：统计索引质量；逻辑：计算精确与候选比例、未解析边界数量及主要原因分布。
    IndexQuality qualityStatistics();
}
