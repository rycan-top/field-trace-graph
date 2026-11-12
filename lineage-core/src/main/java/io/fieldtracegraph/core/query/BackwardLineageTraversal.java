package io.fieldtracegraph.core.query;

import io.fieldtracegraph.core.model.TraceEdge;

import java.util.ArrayList;
import java.util.List;

// 作用：反向血缘遍历，沿数据边向上游查找来源。
// 使用场景：trace --direction in，回答“这个值从哪来”。

/**
 * Owns the reverse value-propagation rules: expanding incoming steps back to
 * their sources and recording the resulting edges and neighbours.
 */
public final class BackwardLineageTraversal {
    // 作用：反向扩展一个值引用；逻辑：取该节点的全部入边，按字段传播规则过滤后转换成上游引用。
    public List<ValueRef> expand(
            LineageIndex index,
            ValueRef current,
            List<Step> steps,
            int currentDepth,
            TraversalBudget budget,
            TraversalState state,
            TraversalFrontier frontier
    ) {
        List<ValueRef> discovered = new ArrayList<>();
        for (Step step : steps) {
            ValueRef previous = step.source();
            TraceEdge edge = step.toEdge(
                    previous,
                    current,
                    "IN",
                    currentDepth + 1,
                    scopeOf(index, previous),
                    scopeOf(index, current),
                    sourceFile(index, step)
            );
            if (!state.recordEdge(edge, budget)) {
                return discovered;
            }
            state.visited.add(previous);
            state.backwardVisited.add(previous);
            if (frontier.visited.add(previous)) {
                frontier.depth.put(previous, currentDepth + 1);
                discovered.add(previous);
            }
        }
        return discovered;
    }

    // 作用：取值引用所属的作用域；逻辑：查节点表，缺失时回退到引用自身携带的作用域。
    private String scopeOf(LineageIndex index, ValueRef ref) {
        var node = index.node(ref.nodeId());
        return node == null ? null : node.scopeSymbolId();
    }

    // 作用：取遍历步对应的源文件；逻辑：按步的证据或节点所属文件查询。
    private String sourceFile(LineageIndex index, Step step) {
        var file = index.file(step.fileId());
        return file == null ? null : file.relativePath();
    }
}
