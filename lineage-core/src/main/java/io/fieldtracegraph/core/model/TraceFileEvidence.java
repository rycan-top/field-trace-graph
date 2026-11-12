package io.fieldtracegraph.core.model;

// 作用：文件与行号级证据。
// 使用场景：source 命令定位结论对应的源码位置。

/**
 * Deduplicated file-level evidence index for one trace. It keeps the snapshot
 * identity of every file that contributed an edge to the result.
 *
 * 作用：文件与行号证据；场景：`source` 命令定位源码。
 */
public record TraceFileEvidence(
        String fileId,
        String relativePath,
        String contentHash,
        int evidenceCount
) {
}
