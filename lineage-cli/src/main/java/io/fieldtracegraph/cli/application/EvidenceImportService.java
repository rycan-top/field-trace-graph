package io.fieldtracegraph.cli.application;

import io.fieldtracegraph.cli.dto.EvidenceImportItem;
import io.fieldtracegraph.cli.project.ProjectIndexPaths;
import io.fieldtracegraph.cli.project.ProjectRootResolver;
import io.fieldtracegraph.core.model.EdgeEvidence;
import io.fieldtracegraph.core.model.EvidenceImportResult;
import io.fieldtracegraph.core.port.IndexRepository;
import io.fieldtracegraph.core.port.IndexRepositoryFactory;
import io.fieldtracegraph.core.support.Ids;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// 作用：把运行时证据写入索引数据库。
// 使用场景：evidence import 命令的核心流程。

/**
 * Imports runtime observations as additive evidence. Import never mutates the
 * static graph and never promotes unrelated edge identities.
 */
public class EvidenceImportService {
    private final ProjectRootResolver projectRootResolver;
    private final IndexRepositoryFactory repositoryFactory;

    // 作用：注入证据导入依赖；逻辑：保存项目解析器与可读写仓库工厂。
    public EvidenceImportService(
            ProjectRootResolver projectRootResolver,
            IndexRepositoryFactory repositoryFactory
    ) {
        this.projectRootResolver = projectRootResolver;
        this.repositoryFactory = repositoryFactory;
    }

    // 作用：导入运行时证据；逻辑：校验索引与项目绑定，逐条确认逻辑边存在后批量写入。
    public EvidenceImportResult importEvidence(
            String explicitProject,
            List<EvidenceImportItem> items
    ) {
        Path root = projectRootResolver.resolve(explicitProject);
        Path databasePath = ProjectIndexPaths.databasePath(root);
        if (!Files.exists(databasePath)) {
            throw new IllegalStateException("INDEX_NOT_FOUND");
        }
        if (items.isEmpty()) {
            return new EvidenceImportResult(0);
        }
        try (IndexRepository repository = repositoryFactory.create(databasePath)) {
            repository.initialize();
            String fingerprint = repository.projectRootFingerprint();
            if (fingerprint != null && !fingerprint.isBlank()
                    && !fingerprint.equals(Ids.sha256(root.toString()))) {
                throw new IllegalStateException("PROJECT_BINDING_CHANGED");
            }
            List<EdgeEvidence> evidence = new ArrayList<>();
            for (EvidenceImportItem item : items) {
                EdgeEvidence converted = toEvidence(item);
                if (!repository.hasLogicalEdge(converted.logicalEdgeKey())) {
                    throw new IllegalArgumentException(
                            "EVIDENCE_LOGICAL_EDGE_NOT_FOUND: " + converted.logicalEdgeKey()
                    );
                }
                evidence.add(converted);
            }
            return new EvidenceImportResult(repository.importEdgeEvidence(evidence));
        }
    }

    // 作用：把导入项转换为证据记录；逻辑：补齐作用域、时间与稳定 ID，并固定标记运行时来源。
    private EdgeEvidence toEvidence(EvidenceImportItem item) {
        String logicalKey = item.logicalEdgeKey();
        if (logicalKey == null || logicalKey.isBlank()) {
            throw new IllegalArgumentException("EVIDENCE_LOGICAL_EDGE_KEY_REQUIRED");
        }
        String coverage = item.coverage();
        if (coverage == null || coverage.isBlank()) {
            throw new IllegalArgumentException("EVIDENCE_COVERAGE_REQUIRED");
        }
        String scope = item.scopeSymbolId();
        if (scope == null || scope.isBlank()) {
            scope = EdgeEvidence.scopeFromLogicalKey(logicalKey);
        }
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("EVIDENCE_SCOPE_REQUIRED");
        }
        String observedAt = item.observedAt() == null || item.observedAt().isBlank()
                ? Instant.now().toString()
                : item.observedAt();
        String evidenceId = item.evidenceId();
        if (evidenceId == null || evidenceId.isBlank()) {
            evidenceId = Ids.sha256(
                    logicalKey,
                    "runtime-observation",
                    item.sourceEdgeId(),
                    item.adapter(),
                    item.rule(),
                    coverage,
                    observedAt
            );
        }
        return new EdgeEvidence(
                evidenceId,
                logicalKey,
                item.sourceEdgeId(),
                "RUNTIME_OBSERVED",
                "RUNTIME_TRACE",
                item.evidenceKind() == null || item.evidenceKind().isBlank()
                        ? "RUNTIME_OBSERVATION"
                        : item.evidenceKind(),
                item.adapter(),
                item.rule(),
                null,
                0,
                0,
                0,
                0,
                item.detailsJson() == null || item.detailsJson().isBlank()
                        ? "{}"
                        : item.detailsJson(),
                coverage,
                observedAt,
                scope
        );
    }
}
