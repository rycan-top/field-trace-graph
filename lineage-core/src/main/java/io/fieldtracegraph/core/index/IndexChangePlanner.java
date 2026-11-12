package io.fieldtracegraph.core.index;

import io.fieldtracegraph.core.model.SourceFileRecord;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

// 作用：对比新旧文件哈希，得出新增、修改与删除的文件集合。
// 使用场景：每次增量索引开始时规划本次变更。

/**
 * Compares the current source manifest and semantic context against the last
 * published generation to derive the change set. Content hashes are the source
 * of truth; mtime is never used for correctness.
 *
 */
public final class IndexChangePlanner {
    // 作用：规划一次增量索引的变更集；逻辑：按文件哈希、上下文指纹与删除列表对比，产出新增、修改、删除三类路径。
    public IndexChangeSet plan(
            List<SourceFileRecord> currentFiles,
            Map<String, String> previousHashes,
            String previousContextHash,
            String currentContextHash
    ) {
        Map<String, String> current = currentFiles.stream()
                .collect(Collectors.toMap(SourceFileRecord::relativePath, SourceFileRecord::contentHash));

        Set<String> added = new LinkedHashSet<>();
        Set<String> modified = new LinkedHashSet<>();
        for (SourceFileRecord file : currentFiles) {
            String previous = previousHashes.get(file.relativePath());
            if (previous == null) {
                added.add(file.relativePath());
            } else if (!previous.equals(file.contentHash())) {
                modified.add(file.relativePath());
            }
        }

        Set<String> deleted = new LinkedHashSet<>();
        for (String path : previousHashes.keySet()) {
            if (!current.containsKey(path)) {
                deleted.add(path);
            }
        }

        boolean contextChanged = !Objects.equals(previousContextHash, currentContextHash);
        return new IndexChangeSet(
                Set.copyOf(added),
                Set.copyOf(modified),
                Set.copyOf(deleted),
                contextChanged
        );
    }
}
