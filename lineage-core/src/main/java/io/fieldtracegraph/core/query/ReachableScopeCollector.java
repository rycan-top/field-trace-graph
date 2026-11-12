package io.fieldtracegraph.core.query;

import io.fieldtracegraph.core.model.CallEdgeRecord;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

// 作用：收集可达的方法范围。
// 使用场景：targets 目标发现与 trace 的范围裁剪。

/**
 * Collects the scopes that can contribute to a field-lineage query. Callees are
 * always included because parameters, return values and receiver writes are
 * produced there. Callers are optional: effective queries stay inside the
 * selected method and its callees, while full diagnostic queries may include
 * call sites that instantiate or invoke the selected method.
 */
public final class ReachableScopeCollector {
    private final int maxDepth;

    // 作用：创建可达范围收集器；逻辑：保存最大扩展深度。
    public ReachableScopeCollector(int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("max callee depth must be positive");
        }
        this.maxDepth = maxDepth;
    }

    // 作用：收集从起点可达的作用域；逻辑：按调用图逐层扩展，超过深度上限停止。
    public Set<String> collect(
            String rootScopeId,
            boolean includeCallers,
            Function<String, List<CallEdgeRecord>> callsByScope,
            Map<String, Set<String>> callersByCallee
    ) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(rootScopeId);
        visited.add(rootScopeId);
        int depth = 0;
        while (!queue.isEmpty() && depth < maxDepth) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                String scopeId = queue.poll();
                result.add(scopeId);
                for (CallEdgeRecord call : callsByScope.apply(scopeId)) {
                    if (call.calleeSymbolId() != null && visited.add(call.calleeSymbolId())) {
                        queue.add(call.calleeSymbolId());
                    }
                }
                if (includeCallers) {
                    for (String callerId : callersByCallee.getOrDefault(scopeId, Set.of())) {
                        if (visited.add(callerId)) {
                            queue.add(callerId);
                        }
                    }
                }
            }
            depth++;
        }
        return result;
    }
}
