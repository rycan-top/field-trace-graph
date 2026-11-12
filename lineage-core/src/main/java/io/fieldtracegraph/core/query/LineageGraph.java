package io.fieldtracegraph.core.query;

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

// 作用：图结构与邻接关系的封装。
// 使用场景：正向、反向遍历时快速取相邻边。

public record LineageGraph(
        MethodCandidate method,
        int generation,
        String graphStatus,
        List<ValueNodeRecord> valueNodes,
        List<DataEdgeRecord> dataEdges,
        List<EdgeEvidence> edgeEvidence,
        List<CallEdgeRecord> callEdges,
        List<PendingRefRecord> pendingRefs,
        List<MethodSummaryRecord> methodSummaries,
        Map<String, SourceFileRecord> sourceFiles,
        Map<String, String> scopeQnames
) {
}
