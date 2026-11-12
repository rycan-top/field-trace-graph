package io.fieldtracegraph.javaanalysis.semantic.protocol;

import java.util.List;

// 作用：主进程发给语义 worker 的请求。
// 使用场景：一次语义分析任务的完整输入。

public record SemanticWorkerRequest(
        int schemaVersion,
        List<SemanticSource> sources,
        List<String> classpath,
        String encoding
) {
    public static final int SCHEMA_VERSION = 1;
}
