package io.fieldtracegraph.javaanalysis.semantic.source;

import io.fieldtracegraph.core.model.SourceFileRecord;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 作用：校验分析前后的源码快照是否一致。
// 使用场景：索引期间源文件被修改时中止发布，避免产出过期结果。

/**
 * Verifies that the bytes analyzed match the bytes about to be published. A
 * mismatch means the source changed between analysis and publish.
 */
public final class SourceSnapshotVerifier {
    // 作用：校验分析与发布时的源码一致；逻辑：按相对路径比对双方内容哈希，缺失或变化即抛出来源已变更异常。
    public void verify(List<SourceFileRecord> analyzed, List<SourceFileRecord> published) {
        Map<String, String> analyzedHashes = analyzed.stream()
                .collect(Collectors.toMap(SourceFileRecord::relativePath, SourceFileRecord::contentHash));
        Map<String, String> publishedHashes = published.stream()
                .collect(Collectors.toMap(SourceFileRecord::relativePath, SourceFileRecord::contentHash));
        for (String path : analyzedHashes.keySet()) {
            if (!publishedHashes.containsKey(path)) {
                throw new IllegalStateException("SOURCE_CHANGED: " + path);
            }
        }
        for (String path : publishedHashes.keySet()) {
            String analyzedHash = analyzedHashes.get(path);
            if (analyzedHash == null || !analyzedHash.equals(publishedHashes.get(path))) {
                throw new IllegalStateException("SOURCE_CHANGED: " + path);
            }
        }
    }
}
