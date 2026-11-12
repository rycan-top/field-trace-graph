package io.fieldtracegraph.core.port;

import io.fieldtracegraph.core.model.AnalysisResult;

import java.nio.file.Path;
import java.util.Set;

// 作用：索引原子发布端口。
// 使用场景：一次索引结束时提交新的 generation，避免读到半成品。

public interface IndexPublisher {
    // 作用：原子发布一次索引结果；逻辑：在单个事务内切换 generation 并写入路径范围内的图数据。
    void publish(
            AnalysisResult analysis,
            Path projectRoot,
            String contextHash,
            String parserVersion,
            String attemptId,
            Set<String> publishPaths
    );
}
