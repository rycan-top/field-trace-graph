package io.fieldtracegraph.core.index;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

// 作用：根据调用关系反推受影响的符号与方法范围。
// 使用场景：增量索引时缩小需要重新分析的代码范围。

/**
 * Computes the transitive closure of caller scopes affected by a set of
 * changed or deleted symbols. The caller index maps a callee symbol id to the
 * set of caller symbol ids that reference it.
 *
 */
public final class AffectedScopeResolver {
    // 作用：解析本次变更影响到的符号集合；逻辑：从变更符号出发，沿调用者与待解析引用反向扩散，直到不再产生新符号。
    public Set<String> resolve(
            Set<String> changedSymbolIds,
            Map<String, Set<String>> callersByCallee,
            Map<String, Set<String>> pendingCallersByReferenceName,
            Set<String> changedQnames
    ) {
        Set<String> affected = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(changedSymbolIds);
        Set<String> visited = new LinkedHashSet<>(changedSymbolIds);
        for (String qname : changedQnames) {
            for (String caller : pendingCallersByReferenceName.getOrDefault(qname, Set.of())) {
                if (visited.add(caller)) {
                    affected.add(caller);
                    queue.add(caller);
                }
            }
        }
        while (!queue.isEmpty()) {
            String callee = queue.poll();
            for (String caller : callersByCallee.getOrDefault(callee, Set.of())) {
                if (visited.add(caller)) {
                    affected.add(caller);
                    queue.add(caller);
                }
            }
        }
        return Set.copyOf(affected);
    }
}
