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
    /**
     * A value can be reached through many call sites. Call context makes those
     * arrivals distinguishable, which is required for attribution, but on a
     * shared helper or a recursive cycle the number of context strings grows
     * combinatorially and turns a bounded query into an unbounded walk.
     * Keeping a fixed number of contexts per physical value preserves the
     * distinction that matters while guaranteeing termination.
     */
    static final int MAX_CONTEXTS_PER_VALUE = 16;
    static final int MAX_VISITED_VALUES = 20_000;

    final Set<ValueRef> visited = new LinkedHashSet<>();
    final Map<ValueRef, Integer> depth = new HashMap<>();
    private final Map<String, Integer> contextsPerValue = new HashMap<>();
    private String capReason;

    // 作用：接纳一个待扩展引用；逻辑：按物理值与总节点数双重限额去重后加入前沿。
    boolean offer(ValueRef ref) {
        if (visited.contains(ref)) {
            return false;
        }
        if (visited.size() >= MAX_VISITED_VALUES) {
            capReason = capReason == null ? "NODE_LIMIT" : capReason;
            return false;
        }
        String physical = ValueRef.withoutCallContext(ref.key());
        int contexts = contextsPerValue.merge(physical, 1, Integer::sum);
        if (contexts > MAX_CONTEXTS_PER_VALUE) {
            capReason = capReason == null ? "CONTEXT_LIMIT" : capReason;
            return false;
        }
        visited.add(ref);
        return true;
    }

    // 作用：返回触发限额的原因；逻辑：没有触发时返回 null。
    String capReason() {
        return capReason;
    }
}
