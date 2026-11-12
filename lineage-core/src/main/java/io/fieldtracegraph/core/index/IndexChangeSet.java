package io.fieldtracegraph.core.index;

import java.util.Set;

// 作用：承载一次索引变更的新增、修改与删除集合。
// 使用场景：传给分析器与发布器，决定重算范围。

/**
 * The set of source and context inputs that changed since the last published
 * generation. Paths are project-relative and use forward slashes.
 *
 */
public record IndexChangeSet(
        Set<String> added,
        Set<String> modified,
        Set<String> deleted,
        boolean contextChanged
) {
    // 作用：判断本次索引是否没有任何变更；逻辑：新增、修改、删除三个集合都为空时返回 true。
    public boolean isEmpty() {
        return added.isEmpty() && modified.isEmpty() && deleted.isEmpty() && !contextChanged;
    }
}
