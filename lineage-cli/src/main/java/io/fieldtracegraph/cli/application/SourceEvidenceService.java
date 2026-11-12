package io.fieldtracegraph.cli.application;

import io.fieldtracegraph.cli.project.ProjectIndexPaths;
import io.fieldtracegraph.cli.project.ProjectRootResolver;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.SourceScanOptions;
import io.fieldtracegraph.core.model.SourceResult;
import io.fieldtracegraph.core.port.IndexRepository;
import io.fieldtracegraph.core.port.IndexRepositoryFactory;
import io.fieldtracegraph.core.port.ProjectSourceReader;
import io.fieldtracegraph.core.shared.text.LineRange;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// 作用：查询结论对应的源码证据。
// 使用场景：source 命令展示文件、行号与代码片段。

/**
 * Reads a bounded source snippet that matches the published index. It verifies the
 * path and content hash before returning text, and never reads outside the
 * project root.
 */
public class SourceEvidenceService {
    private final ProjectRootResolver projectRootResolver;
    private final ProjectSourceReader sourceReader;
    private final IndexRepositoryFactory repositoryFactory;

    // 作用：注入源码证据依赖；逻辑：保存项目解析器、源文件读取器与只读仓库工厂。
    public SourceEvidenceService(
            ProjectRootResolver projectRootResolver,
            ProjectSourceReader sourceReader,
            IndexRepositoryFactory repositoryFactory
    ) {
        this.projectRootResolver = projectRootResolver;
        this.sourceReader = sourceReader;
        this.repositoryFactory = repositoryFactory;
    }

    // 作用：读取源码片段；逻辑：采集当前源文件并比对索引哈希，命中后按行范围返回文本。
    public SourceResult read(String explicitProject, String relativePath, int startLine, int endLine, SourceScanOptions options) {
        LineRange requestedRange = LineRange.of(startLine, endLine);
        Path root = projectRootResolver.resolve(explicitProject);
        Path databasePath = ProjectIndexPaths.databasePath(root);
        if (!Files.exists(databasePath)) {
            throw new IllegalStateException("INDEX_MISSING");
        }
        try (IndexRepository repository = repositoryFactory.createReadOnly(databasePath)) {
            List<SourceFileRecord> files = sourceReader.capture(root, options);
            SourceFileRecord file = files.stream()
                    .filter(candidate -> candidate.relativePath().equals(relativePath))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("SOURCE_FILE_NOT_FOUND"));
            String indexedHash = repository.sourceFileHashes().get(relativePath);
            if (indexedHash == null || !indexedHash.equals(file.contentHash())) {
                throw new IllegalArgumentException("SOURCE_CHANGED");
            }
            List<String> lines = splitLines(file.content());
            int start = Math.max(1, requestedRange.startLine());
            int end = Math.min(lines.size(), Math.max(start, requestedRange.endLine()));
            List<String> selected = new ArrayList<>();
            for (int index = start; index <= end; index++) {
                selected.add(lines.get(index - 1));
            }
            return new SourceResult(relativePath, file.contentHash(), start, end, String.join("\n", selected));
        }
    }

    // 作用：拆分文本行；逻辑：按行终止符切分并去掉末尾空行。
    private List<String> splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        String[] raw = content.split("\\R", -1);
        List<String> lines = new ArrayList<>(List.of(raw));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}
