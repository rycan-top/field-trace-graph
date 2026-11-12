package io.fieldtracegraph.core.query;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

// 作用：遍历前沿队列。
// 使用场景：逐层扩展图节点，支持广度优先遍历。

/**
 * Per-direction traversal bookkeeping. Forward and backward scans must not
 * share this state: an {@code in} lookup followed by an {@code out} lookup from
 * every discovered node would otherwise walk the whole connected component
 * instead of producing two independent lineage cones.
 */
final class TraversalFrontier {
    final Set<ValueRef> visited = new LinkedHashSet<>();
    final Map<ValueRef, Integer> depth = new HashMap<>();
}
