package io.fieldtracegraph.cli.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fieldtracegraph.cli.project.ProjectRootResolver;
import io.fieldtracegraph.core.model.AmbiguousMethodException;
import io.fieldtracegraph.core.model.BoundaryReason;
import io.fieldtracegraph.core.model.SourceScanOptions;
import io.fieldtracegraph.core.model.TraceResult;
import io.fieldtracegraph.core.query.CallScopeMode;
import io.fieldtracegraph.core.query.TraceCompleteness;
import io.fieldtracegraph.core.query.TraceQuality;
import io.fieldtracegraph.core.query.TraceTimeoutException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

// 作用：对一个索引批量执行 method/target 追踪。
// 使用场景：大规模回归审计，避免每个用例重复启动 JVM 与重复打开快照。

/**
 * Runs many traces against one already-published index.
 *
 * <p>Each single {@code ftg trace} invocation pays JVM startup plus index
 * readiness checks. Auditing thousands of method/target pairs that way is
 * dominated by fixed cost, so this service resolves the project and prepares
 * the index once, then loops over read-only queries writing one JSON object
 * per case.</p>
 */
public class BatchTraceService {
    private static final int PROGRESS_EVERY = 100;

    private final ProjectRootResolver projectRootResolver;
    private final IndexReadinessService indexReadinessService;
    private final LineageQueryService lineageQueryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 作用：注入批量追踪依赖；逻辑：保存项目解析、索引就绪与查询服务。
    public BatchTraceService(
            ProjectRootResolver projectRootResolver,
            IndexReadinessService indexReadinessService,
            LineageQueryService lineageQueryService
    ) {
        this.projectRootResolver = projectRootResolver;
        this.indexReadinessService = indexReadinessService;
        this.lineageQueryService = lineageQueryService;
    }

    // 作用：读取用例文件；逻辑：忽略空行与注释，按首个制表符拆分 method 与 target。
    public static List<Case> readCases(Path file) throws IOException {
        List<Case> cases = new ArrayList<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 0) {
                tab = line.indexOf('|');
            }
            if (tab < 0) {
                throw new IllegalArgumentException("Case line must be 'method<TAB>target': " + raw);
            }
            String method = line.substring(0, tab).strip();
            String target = line.substring(tab + 1).strip();
            if (!method.isEmpty() && !target.isEmpty()) {
                cases.add(new Case(method, target));
            }
        }
        return cases;
    }

    // 作用：执行批量追踪；逻辑：索引就绪一次后逐条查询并追加一行 JSON。
    public Summary run(
            String explicitProject,
            List<Case> cases,
            Path out,
            Path textDir,
            int textMax,
            String direction,
            boolean noSync,
            boolean allowStale,
            long queryTimeoutMillis,
            SourceScanOptions options,
            boolean includeDependencies,
            boolean resume,
            int limit,
            Function<TraceResult, String> renderer
    ) throws IOException {
        Path root = projectRootResolver.resolve(explicitProject);
        indexReadinessService.ensureReady(explicitProject, noSync, allowStale, options);

        List<Case> queue = selectQueue(cases, out, resume, limit);
        if (textDir != null) {
            Files.createDirectories(textDir);
        }
        int written = 0;
        int textWritten = 0;
        long started = System.nanoTime();
        try (BufferedWriter writer = Files.newBufferedWriter(
                out, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND)) {
            for (int i = 0; i < queue.size(); i++) {
                Case item = queue.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("method", item.method());
                row.put("target", item.target());
                long caseStart = System.nanoTime();
                try {
                    TraceResult result = lineageQueryService.trace(
                            root,
                            item.method(),
                            item.target(),
                            direction,
                            -1,
                            -1,
                            queryTimeoutMillis,
                            options,
                            CallScopeMode.EFFECTIVE,
                            includeDependencies,
                            false
                    );
                    describe(row, result);
                    if (textDir != null && textWritten < textMax
                            && !"COMPLETE".equals(row.get("completeness"))) {
                        Path textFile = textDir.resolve(String.format(
                                "%05d__%s.txt", i, sanitize(item.target())));
                        Files.writeString(textFile, renderer.apply(result), StandardCharsets.UTF_8);
                        row.put("text", textFile.toString());
                        textWritten++;
                    }
                    row.put("ok", true);
                } catch (AmbiguousMethodException e) {
                    describeAmbiguous(row, e);
                } catch (TraceTimeoutException e) {
                    describeTimeout(row, e);
                } catch (OutOfMemoryError e) {
                    /*
                     * One oversized call cone must not kill the whole audit
                     * shard. The failed allocation is a transient large array,
                     * so the remaining cases can still run; the row stays
                     * auditable and tells the operator to raise -Xmx.
                     */
                    row.put("ok", false);
                    row.put("noMatch", "TRACE_OOM");
                    row.put("truncated", true);
                    row.put("quality", "OOM");
                    row.put("completeness", "UNKNOWN");
                    row.put("completenessReasons", List.of("TRACE_OOM"));
                    row.put("error", "OutOfMemoryError: case exceeded the trace-batch heap; "
                            + "rerun with a larger -Xmx");
                } catch (Exception e) {
                    row.put("ok", false);
                    row.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                row.put("ms", (System.nanoTime() - caseStart) / 1_000_000L);
                writer.write(objectMapper.writeValueAsString(row));
                writer.newLine();
                written++;
                if (written % PROGRESS_EVERY == 0 || i + 1 == queue.size()) {
                    double secs = (System.nanoTime() - started) / 1_000_000_000d;
                    System.err.printf(
                            "[%d/%d] %.0fs avg=%.0fms %s%n",
                            i + 1, queue.size(), secs, secs * 1000d / (i + 1), item.target());
                }
            }
        }
        return new Summary(root, cases.size(), queue.size(), written, textWritten);
    }

    // 作用：选择待跑用例；逻辑：恢复模式下跳过输出文件中已有的 method/target，并应用上限。
    private List<Case> selectQueue(List<Case> cases, Path out, boolean resume, int limit) throws IOException {
        List<Case> queue = new ArrayList<>();
        if (resume && Files.isRegularFile(out)) {
            var done = repairAndCollectDone(out);
            for (Case item : cases) {
                if (!done.contains(item.method() + "\u0000" + item.target())) {
                    queue.add(item);
                }
            }
        } else {
            queue.addAll(cases);
        }
        if (limit > 0 && queue.size() > limit) {
            return new ArrayList<>(queue.subList(0, limit));
        }
        return queue;
    }

    /*
     * A shard that is killed mid-write leaves a partial JSON line behind. The
     * old resume path simply ignored it, so the file kept a corrupt record
     * forever and the same case was traced a second time next to it. Resume now
     * rewrites the ndjson in place: partial lines are dropped and repeated
     * (method, target) rows are collapsed, so one case has exactly one row.
     */
    // 作用：修复审计文件并收集已完成用例；逻辑：丢弃残行、按 method/target 去重，必要时原子重写。
    private Set<String> repairAndCollectDone(Path out) throws IOException {
        Set<String> done = new LinkedHashSet<>();
        List<String> valid = new ArrayList<>();
        boolean dirty = false;
        try (BufferedReader reader = Files.newBufferedReader(out, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    dirty = true;
                    continue;
                }
                try {
                    var node = objectMapper.readTree(line);
                    String key = node.path("method").asText() + "\u0000" + node.path("target").asText();
                    if (!done.add(key)) {
                        dirty = true;
                        continue;
                    }
                    valid.add(line);
                } catch (Exception damaged) {
                    dirty = true;
                }
            }
        }
        if (dirty) {
            Path tmp = out.resolveSibling(out.getFileName() + ".repair");
            try (BufferedWriter writer = Files.newBufferedWriter(
                    tmp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                for (String keep : valid) {
                    writer.write(keep);
                    writer.newLine();
                }
            }
            try {
                Files.move(tmp, out,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return done;
    }

    // 作用：把追踪结果写成审计字段；逻辑：保留质量、完整性、弱跳原因与链路摘要。
    private void describe(Map<String, Object> row, TraceResult result) {
        TraceQuality quality = TraceQuality.from(result, result.allowCandidate(), result.failOnCandidate());
        TraceCompleteness completeness = TraceCompleteness.from(result);
        row.put("quality", quality.status().name());
        row.put("completeness", completeness.status().name());
        row.put("completenessReasons", completeness.reasons().stream()
                .map(TraceCompleteness.Reason::code).toList());
        row.put("weak", quality.weakHopCount());
        row.put("chains", result.chains().size());
        row.put("edges", result.edges().size());
        row.put("nodes", result.nodes().size());
        row.put("unresolvedField", quality.valueUnresolvedEdges());
        row.put("valueEdges", quality.valueEdges());
        row.put("candidateEdges", quality.candidateEdges());
        row.put("truncated", result.truncated());
        row.put("cycle", result.cycleDetected());
        row.put("noMatch", result.noMatchReason() == null ? "" : result.noMatchReason().code());
        row.put("methodQname", result.methodQname());
        Map<String, Integer> boundaries = new LinkedHashMap<>();
        result.boundaries().forEach(boundary -> {
            String code = boundary.reasonCode();
            if (code == null || code.isBlank()) {
                code = BoundaryReason.from(boundary.reason()).name();
            }
            boundaries.merge(code, 1, Integer::sum);
        });
        row.put("boundaryReasons", boundaries);
        Map<String, Integer> leaves = new LinkedHashMap<>();
        result.leaves().forEach(leaf -> leaves.merge(
                leaf.reason() == null || leaf.reason().isBlank() ? "UNKNOWN" : leaf.reason(),
                1, Integer::sum));
        row.put("leafReasons", leaves);
        Map<String, Integer> weakReasons = new LinkedHashMap<>();
        result.weakHops().forEach(hop -> weakReasons.merge(
                hop.reasonCode() == null ? "UNKNOWN" : hop.reasonCode(), 1, Integer::sum));
        row.put("weakReasons", weakReasons);
        Map<String, Integer> candidateOps = new LinkedHashMap<>();
        Map<String, Integer> provenance = new LinkedHashMap<>();
        result.edges().forEach(edge -> {
            String prov = edge.provenance() == null ? "UNKNOWN" : edge.provenance();
            provenance.merge(prov, 1, Integer::sum);
            if ("CANDIDATE".equals(edge.resolution())) {
                String op = edge.operation() == null || edge.operation().isBlank()
                        ? String.valueOf(edge.edgeType())
                        : edge.operation();
                candidateOps.merge(op, 1, Integer::sum);
            }
        });
        row.put("candidateOps", candidateOps);
        row.put("provenance", provenance);
    }

    // 作用：把重复方法选择器写成审计字段；逻辑：不猜测目标文件，标记 METHOD_AMBIGUOUS
    // 并保留候选模块与签名，让批量统计把歧义与真正的执行错误区分开。
    private void describeAmbiguous(Map<String, Object> row, AmbiguousMethodException exception) {
        row.put("quality", "UNKNOWN");
        row.put("completeness", "UNKNOWN");
        row.put("completenessReasons", List.of("METHOD_AMBIGUOUS"));
        row.put("weak", 0);
        row.put("chains", 0);
        row.put("edges", 0);
        row.put("nodes", 0);
        row.put("unresolvedField", 0);
        row.put("valueEdges", 0);
        row.put("candidateEdges", 0);
        row.put("truncated", false);
        row.put("cycle", false);
        row.put("noMatch", "METHOD_AMBIGUOUS");
        row.put("ambiguousCandidates", exception.candidates().size());
        row.put("ambiguous", exception.candidates().stream()
                .map(candidate -> candidate.moduleId() + "::" + candidate.methodQname())
                .distinct()
                .toList());
        row.put("ok", true);
    }

    // 作用：把超时写成审计字段；逻辑：标记 TRACE_TIMEOUT 与阶段，保持字段形状与正常行一致。
    private void describeTimeout(Map<String, Object> row, TraceTimeoutException exception) {
        /*
         * A budget timeout is not the same as "we do not know anything here":
         * the case was cut short and must stay distinguishable from cases that
         * simply had no usable evidence. Reporting it as UNKNOWN mixed both
         * populations in every dashboard.
         */
        row.put("quality", "TIMEOUT");
        row.put("completeness", "UNKNOWN");
        row.put("completenessReasons", List.of("TRACE_TIMEOUT"));
        row.put("weak", 0);
        row.put("chains", 0);
        row.put("edges", 0);
        row.put("nodes", 0);
        row.put("unresolvedField", 0);
        row.put("valueEdges", 0);
        row.put("candidateEdges", 0);
        row.put("truncated", true);
        row.put("cycle", false);
        row.put("noMatch", "TRACE_TIMEOUT");
        row.put("timeoutPhase", exception.phase());
        row.put("ok", true);
    }

    // 作用：清理文件名；逻辑：把路径分隔符替换为下划线。
    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._$-]", "_");
    }

    public record Case(String method, String target) {
    }

    public record Summary(Path root, int declaredCases, int executedCases, int written, int textWritten) {
    }
}
