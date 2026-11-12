package io.fieldtracegraph.core.model;

import java.util.List;

// 作用：证据差异的汇总结果。
// 使用场景：evidence diff 命令输出与 CI 比对。

public record EvidenceDiffResult(
        int observedCount,
        int matchedCount,
        int missingCount,
        double matchRatio,
        List<EvidenceDiffItem> items
) {
    // 作用：规范化差异条目列表；逻辑：空值转为空列表，非空转为不可变副本。
    public EvidenceDiffResult {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
