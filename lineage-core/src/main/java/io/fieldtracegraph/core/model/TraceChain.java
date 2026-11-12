package io.fieldtracegraph.core.model;

import java.util.List;

// 作用：组装后的可读血缘链路。
// 使用场景：text 输出中展示从起点到终点的完整路径。

// 作用：组装后的可读链路与单跳；场景：text 输出的链路展示。
public record TraceChain(
        String chainId,
        List<String> nodeIds,
        List<String> edgeIds,
        int weakHops,
        List<TraceChainHop> hops
) {
}
