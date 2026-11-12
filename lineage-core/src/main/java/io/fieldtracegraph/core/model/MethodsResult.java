package io.fieldtracegraph.core.model;

import java.util.List;

// 作用：方法搜索的结果集合。
// 使用场景：methods 命令的 text/JSON 输出。

public record MethodsResult(
        int total,
        int offset,
        int limit,
        List<MethodCandidate> candidates
) {
}
