package io.fieldtracegraph.core.model;

import java.util.List;

// 作用：目标发现结果。
// 使用场景：targets 命令的 text/JSON 输出。

public record TargetsResult(
        String methodId,
        String methodQname,
        int total,
        int offset,
        int limit,
        TargetStatistics statistics,
        List<TargetCandidate> targets
) {
}
