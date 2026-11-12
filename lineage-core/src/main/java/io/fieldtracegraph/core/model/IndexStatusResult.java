package io.fieldtracegraph.core.model;

// 作用：索引状态结果。
// 使用场景：status 命令展示新鲜度、代数、文件与符号数量，以及增量判定。

public record IndexStatusResult(
        int indexGeneration,
        String indexStatus,
        String freshness,
        String parserVersion,
        String contextHash,
        String projectRootFingerprint,
        String lastAttemptStatus,
        String lastErrorCode,
        String lastErrorMessage,
        int sourceFileCount,
        int symbolCount,
        SymbolStatistics symbolStatistics,
        int valueNodeCount,
        int dataEdgeCount,
        int callEdgeCount,
        int pendingRefCount,
        IndexQuality quality
) {
    // 作用：构造不带质量指标的兼容状态结果；逻辑：把 quality 补为 null 后转调完整构造器。
    public IndexStatusResult(
            int indexGeneration,
            String indexStatus,
            String freshness,
            String parserVersion,
            String contextHash,
            String projectRootFingerprint,
            String lastAttemptStatus,
            String lastErrorCode,
            String lastErrorMessage,
            int sourceFileCount,
            int symbolCount,
            SymbolStatistics symbolStatistics,
            int valueNodeCount,
            int dataEdgeCount,
            int callEdgeCount,
            int pendingRefCount
    ) {
        this(indexGeneration, indexStatus, freshness, parserVersion, contextHash,
                projectRootFingerprint, lastAttemptStatus, lastErrorCode,
                lastErrorMessage, sourceFileCount, symbolCount, symbolStatistics,
                valueNodeCount, dataEdgeCount, callEdgeCount, pendingRefCount, null);
    }

    // 作用：复制状态结果并附加质量指标；逻辑：保留全部状态字段，只替换 quality。
    public IndexStatusResult withQuality(IndexQuality value) {
        return new IndexStatusResult(
                indexGeneration,
                indexStatus,
                freshness,
                parserVersion,
                contextHash,
                projectRootFingerprint,
                lastAttemptStatus,
                lastErrorCode,
                lastErrorMessage,
                sourceFileCount,
                symbolCount,
                symbolStatistics,
                valueNodeCount,
                dataEdgeCount,
                callEdgeCount,
                pendingRefCount,
                value
        );
    }
}
