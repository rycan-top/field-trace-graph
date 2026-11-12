package io.fieldtracegraph.cli.application;

import io.fieldtracegraph.cli.config.LineageRuntimeProperties;
import io.fieldtracegraph.cli.contract.ProjectContractLoader;
import io.fieldtracegraph.cli.contract.ProjectContracts;
import io.fieldtracegraph.cli.log.CliLog;
import io.fieldtracegraph.cli.project.ProjectIndexPaths;
import io.fieldtracegraph.cli.project.ProjectRootResolver;
import io.fieldtracegraph.cli.project.ProjectWriteLock;
import io.fieldtracegraph.core.index.AffectedScopeResolver;
import io.fieldtracegraph.core.index.IndexChangePlanner;
import io.fieldtracegraph.core.index.IndexChangeSet;
import io.fieldtracegraph.core.model.AnalysisResult;
import io.fieldtracegraph.core.model.Diagnostic;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.IndexStatusResult;
import io.fieldtracegraph.core.model.Provenance;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.SourceScanOptions;
import io.fieldtracegraph.core.model.SymbolRecord;
import io.fieldtracegraph.core.port.IndexRepository;
import io.fieldtracegraph.core.port.IndexRepositoryFactory;
import io.fieldtracegraph.core.port.ProjectSourceReader;
import io.fieldtracegraph.core.port.SourceAnalyzer;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.semantic.context.SemanticContextFingerprint;
import io.fieldtracegraph.javaanalysis.semantic.source.SourceSnapshotVerifier;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// 作用：索引主流程，负责扫描、增量规划、分析、校验与原子发布。
// 使用场景：index、init 以及查询前的自动同步。

/**
 * Builds and atomically publishes the local index. AST and semantic analysis
 * run outside the SQLite write transaction; only the final publish commits.
 */
public class ProjectIndexService {
    private final ProjectRootResolver projectRootResolver;
    private final ProjectSourceReader sourceReader;
    private final SourceAnalyzer sourceAnalyzer;
    private final IndexRepositoryFactory repositoryFactory;
    private final SemanticContextFingerprint contextFingerprint = new SemanticContextFingerprint();
    private final ProjectContractLoader contractLoader = new ProjectContractLoader();
    private final IndexChangePlanner changePlanner = new IndexChangePlanner();
    private final AffectedScopeResolver affectedScopeResolver = new AffectedScopeResolver();
    private final SourceSnapshotVerifier sourceSnapshotVerifier = new SourceSnapshotVerifier();
    private final LineageRuntimeProperties runtimeOptions;

    // 作用：注入索引依赖；逻辑：保存项目解析、源采集、分析器、仓库工厂与运行配置。
    public ProjectIndexService(
            ProjectRootResolver projectRootResolver,
            ProjectSourceReader sourceReader,
            SourceAnalyzer sourceAnalyzer,
            IndexRepositoryFactory repositoryFactory,
            LineageRuntimeProperties runtimeOptions
    ) {
        this.projectRootResolver = projectRootResolver;
        this.sourceReader = sourceReader;
        this.sourceAnalyzer = sourceAnalyzer;
        this.repositoryFactory = repositoryFactory;
        this.runtimeOptions = runtimeOptions;
    }

    // 作用：同步索引；逻辑：加写锁后比对快照，规划增量、执行分析、校验一致性并原子发布。
    public IndexStatusResult synchronize(
            String explicitProject,
            boolean full,
            long lockTimeoutMillis,
            long indexTimeoutMillis,
            SourceScanOptions options
    ) {
        Path root = projectRootResolver.resolve(explicitProject);
        Path lockPath = ProjectIndexPaths.toolDirectory(root).resolve("index.lock");
        try (ProjectWriteLock ignored = ProjectWriteLock.acquire(lockPath, lockTimeoutMillis)) {
            try (IndexRepository repository = repositoryFactory.create(ProjectIndexPaths.databasePath(root))) {
                repository.initialize();
                List<SourceFileRecord> files = sourceReader.capture(root, options);
                String contextHash = contextFingerprint.compute(
                        files,
                        runtimeOptions.parserVersion(),
                        root,
                        options.includeTests(),
                        options.classpathEntries()
                );
                Map<String, String> previousHashes = repository.sourceFileHashes();
                String previousContextHash = repository.contextHash();
                if (!full && repository.isReady()
                        && previousHashes.equals(currentHashes(files))
                        && contextHash.equals(repository.contextHash())
                        && Ids.sha256(root.toString()).equals(repository.projectRootFingerprint())) {
                    return repository.toStatusResult("FRESH");
                }
                IndexChangeSet changeSet = changePlanner.plan(files, previousHashes, previousContextHash, contextHash);
                Set<String> changedSymbolIds = repository.symbolIdsByRelativePaths(
                        union(changeSet.added(), changeSet.modified(), changeSet.deleted())
                );
                Set<String> changedQnames = repository.qnamesByRelativePaths(
                        union(changeSet.added(), changeSet.modified(), changeSet.deleted())
                );
                String attemptId = java.util.UUID.randomUUID().toString();
                repository.beginAttempt(attemptId, repository.generation(), files, previousHashes, changeSet.deleted());
                long deadline = System.nanoTime() + indexTimeoutMillis * 1_000_000L;
                AnalysisResult analysis;
                try {
                    analysis = sourceAnalyzer.analyze(files, root, options);
                } catch (RuntimeException e) {
                    repository.markFailedAttempt(attemptId, "INDEX_ANALYSIS_FAILED", e.getMessage());
                    throw e;
                }
                if (System.nanoTime() > deadline) {
                    repository.markFailedAttempt(attemptId, "INDEX_TIMEOUT", "Index exceeded the time budget");
                    throw new IllegalStateException("INDEX_TIMEOUT");
                }
                analysis = markGeneratedSources(analysis, files, root, options);
                if (analysis.diagnostics().stream().anyMatch(Diagnostic::blocking)) {
                    Diagnostic first = analysis.diagnostics().stream().filter(Diagnostic::blocking).findFirst().orElseThrow();
                    repository.markFailedAttempt(attemptId, first.code(), first.message());
                    throw new IllegalStateException("INDEX_ANALYSIS_FAILED: " + first.code());
                }
                List<SourceFileRecord> currentFiles = sourceReader.capture(root, options);
                sourceSnapshotVerifier.verify(files, currentFiles);

                Set<String> changedFileIds = files.stream()
                        .filter(file -> changeSet.added().contains(file.relativePath())
                                || changeSet.modified().contains(file.relativePath()))
                        .map(SourceFileRecord::fileId)
                        .collect(Collectors.toSet());
                Set<String> newQnames = analysis.symbols().stream()
                        .filter(symbol -> changedFileIds.contains(symbol.fileId()))
                        .map(SymbolRecord::qname)
                        .collect(Collectors.toSet());
                Set<String> removedQnames = new LinkedHashSet<>(changedQnames);
                removedQnames.removeAll(newQnames);
                Set<String> relevantQnames = union(changedQnames, newQnames);
                Set<String> affectedScopes = affectedScopeResolver.resolve(
                        changedSymbolIds,
                        repository.callersByCallee(),
                        repository.pendingRefCallersByReferenceName(),
                        relevantQnames
                );
                if (!changeSet.isEmpty()) {
                    CliLog.info("indexing: added=" + changeSet.added().size()
                            + " modified=" + changeSet.modified().size()
                            + " deleted=" + changeSet.deleted().size()
                            + " affectedScopes=" + affectedScopes.size());
                }

                boolean fullPublish = full || changeSet.contextChanged() || !removedQnames.isEmpty();
                Set<String> publishPaths = fullPublish
                        ? null
                        : union(changeSet.added(), changeSet.modified(), changeSet.deleted(),
                                repository.relativePathsBySymbolIds(affectedScopes));
                repository.publish(
                        analysis,
                        root,
                        contextHash,
                        runtimeOptions.parserVersion(),
                        attemptId,
                        publishPaths
                );
                return repository.toStatusResult("FRESH");
            }
        }
    }

    // 作用：汇总当前文件哈希；逻辑：把源文件列表转换为路径到内容哈希的映射。
    private Map<String, String> currentHashes(List<SourceFileRecord> files) {
        return files.stream().collect(Collectors.toMap(SourceFileRecord::relativePath, SourceFileRecord::contentHash));
    }

    // 作用：标记生成源码来源；逻辑：按生成源根目录识别文件，把其源码边升级为 GENERATED_SOURCE。
    private AnalysisResult markGeneratedSources(
            AnalysisResult analysis,
            List<SourceFileRecord> files,
            Path root,
            SourceScanOptions options
    ) {
        if (options.generatedSourceRoots().isEmpty()) {
            ProjectContracts contracts = contractLoader.load(root);
            if (contracts.generatedSources().isEmpty()) {
                return analysis;
            }
        }
        ProjectContracts contracts = contractLoader.load(root);
        List<String> configuredRoots = new java.util.ArrayList<>(options.generatedSourceRoots());
        configuredRoots.addAll(contracts.generatedSources());
        List<Path> generatedRoots = configuredRoots.stream().distinct()
                .map(value -> root.resolve(value).toAbsolutePath().normalize())
                .toList();
        Set<String> generatedFileIds = new HashSet<>();
        for (SourceFileRecord file : files) {
            if (file.absolutePath() == null) {
                continue;
            }
            Path path = file.absolutePath().toAbsolutePath().normalize();
            if (generatedRoots.stream().anyMatch(path::startsWith)) {
                generatedFileIds.add(file.fileId());
            }
        }
        if (generatedFileIds.isEmpty()) {
            return analysis;
        }
        List<DataEdgeRecord> edges = analysis.dataEdges().stream()
                .map(edge -> {
                    if (!generatedFileIds.contains(edge.fileId())
                            || !Provenance.SOURCE.name().equals(edge.provenance())) {
                        return edge;
                    }
                    return edge.withProvenance(Provenance.GENERATED_SOURCE.name());
                })
                .toList();
        return new AnalysisResult(
                analysis.sourceFiles(),
                analysis.symbols(),
                analysis.valueNodes(),
                edges,
                analysis.callEdges(),
                analysis.pendingRefs(),
                analysis.methodSummaries(),
                analysis.diagnostics()
        );
    }

    // 作用：合并三个集合；逻辑：按顺序放入有序集合后返回。
    private Set<String> union(Set<String> first, Set<String> second, Set<String> third) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.addAll(first);
        result.addAll(second);
        result.addAll(third);
        return result;
    }

    // 作用：合并四个集合；逻辑：按顺序放入有序集合后返回。
    private Set<String> union(Set<String> first, Set<String> second, Set<String> third, Set<String> fourth) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.addAll(first);
        result.addAll(second);
        result.addAll(third);
        result.addAll(fourth);
        return result;
    }

    // 作用：合并两个集合；逻辑：按顺序放入有序集合后返回。
    private Set<String> union(Set<String> first, Set<String> second) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.addAll(first);
        result.addAll(second);
        return result;
    }
}
