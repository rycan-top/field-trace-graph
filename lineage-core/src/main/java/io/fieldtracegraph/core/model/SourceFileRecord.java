package io.fieldtracegraph.core.model;

import java.nio.file.Path;

// 作用：源文件元数据与内容哈希。
// 使用场景：增量索引判定、分析前后快照一致性与文件级证据。

public record SourceFileRecord(
        String fileId,
        String relativePath,
        String moduleId,
        String contentHash,
        long fileSize,
        String encoding,
        String languageLevel,
        String analysisStatus,
        String analysisIssueCode,
        String content,
        Path absolutePath
) {
    // 作用：替换文件内容并更新哈希；逻辑：复制记录，写入新内容并重新计算 SHA-256。
    public SourceFileRecord withContent(String sourceContent) {
        return new SourceFileRecord(
                fileId,
                relativePath,
                moduleId,
                contentHash,
                fileSize,
                encoding,
                languageLevel,
                analysisStatus,
                analysisIssueCode,
                sourceContent,
                absolutePath
        );
    }

    // 作用：更新文件的分析状态；逻辑：复制记录，替换状态与问题码字段。
    public SourceFileRecord withAnalysisStatus(String status, String issueCode) {
        return new SourceFileRecord(
                fileId,
                relativePath,
                moduleId,
                contentHash,
                fileSize,
                encoding,
                languageLevel,
                status,
                issueCode,
                content,
                absolutePath
        );
    }
}
