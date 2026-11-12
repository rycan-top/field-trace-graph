package io.fieldtracegraph.core.model;

import java.util.List;

// 作用：目标维度统计。
// 使用场景：targets 命令展示候选数量与置信分布。

/**
 * Separates field-lineage targets from call-expression bookkeeping. The
 * comparable total deliberately excludes CALL_RESULT nodes; call results are
 * still queryable but are not object or field lineage targets.
 */
public record TargetStatistics(
        int totalCount,
        int comparableCount,
        int fieldCount,
        int objectRootCount,
        int returnCount,
        int callResultCount
) {
    // 作用：从目标候选列表计算统计；逻辑：按解析强度分组计数并汇总总数。
    public static TargetStatistics from(List<TargetCandidate> targets) {
        int fieldCount = 0;
        int objectRootCount = 0;
        int returnCount = 0;
        int callResultCount = 0;
        for (TargetCandidate target : targets) {
            switch (target.kind()) {
                case "FIELD" -> fieldCount++;
                case "PARAMETER", "LOCAL" -> objectRootCount++;
                case "RETURN" -> returnCount++;
                case "CALL_RESULT", "CONSTRUCTOR_RESULT" -> callResultCount++;
                default -> {
                }
            }
        }
        int comparableCount = fieldCount + objectRootCount + returnCount;
        return new TargetStatistics(
                targets.size(),
                comparableCount,
                fieldCount,
                objectRootCount,
                returnCount,
                callResultCount
        );
    }
}
