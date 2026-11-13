package io.fieldtracegraph.cli.view;

import io.fieldtracegraph.core.model.CommandResult;
import io.fieldtracegraph.core.model.DoctorResult;
import io.fieldtracegraph.core.model.EvidenceImportResult;
import io.fieldtracegraph.core.model.EvidenceDiffResult;
import io.fieldtracegraph.core.model.IndexStatusResult;
import io.fieldtracegraph.core.model.MethodsResult;
import io.fieldtracegraph.core.model.SourceResult;
import io.fieldtracegraph.core.model.TargetsResult;
import io.fieldtracegraph.core.model.TraceResult;
import io.fieldtracegraph.core.query.TraceCompleteness;
import io.fieldtracegraph.core.query.TraceQuality;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：输出人类可读的文本结果。
// 使用场景：默认终端输出，并在正文后追加诊断信息。

public class TextResultView {
    // 作用：渲染文本结果；逻辑：先输出正文，再在末尾追加诊断码与消息。
    public String render(CommandResult result) {
        String body = renderBody(result);
        if (result.data() == null || result.diagnostics().isEmpty()) {
            return body;
        }
        StringBuilder builder = new StringBuilder(body);
        if (!body.endsWith("\n")) {
            builder.append('\n');
        }
        for (var diagnostic : result.diagnostics()) {
            builder.append("Diagnostic: ").append(diagnostic.code())
                    .append(": ").append(diagnostic.message())
                    .append('\n');
        }
        return builder.toString();
    }

    // 作用：按数据类型渲染正文；逻辑：分派到方法、目标、追踪、状态、源码、体检与证据等格式化方法。
    private String renderBody(CommandResult result) {
        if (result.data() instanceof MethodsResult methods) {
            return methodsText(methods);
        }
        if (result.data() instanceof TargetsResult targets) {
            return targetsText(targets);
        }
        if (result.data() instanceof TraceResult trace) {
            return traceText(trace);
        }
        if (result.data() instanceof IndexStatusResult status) {
            return statusText(status);
        }
        if (result.data() instanceof SourceResult source) {
            return source.text();
        }
        if (result.data() instanceof DoctorResult doctor) {
            return doctorText(doctor);
        }
        if (result.data() instanceof EvidenceImportResult imported) {
            return "Imported evidence: " + imported.importedEvidenceCount();
        }
        if (result.data() instanceof String text) {
            return text;
        }
        if (result.data() instanceof EvidenceDiffResult diff) {
            StringBuilder builder = new StringBuilder();
            builder.append("Runtime evidence: observed=").append(diff.observedCount())
                    .append(" matched=").append(diff.matchedCount())
                    .append(" missing=").append(diff.missingCount())
                    .append(" matchRatio=")
                    .append(percent(diff.matchRatio()))
                    .append('\n');
            for (var item : diff.items()) {
                builder.append(item.presentInStaticGraph() ? "MATCH\t" : "MISSING\t")
                        .append(item.logicalEdgeKey())
                        .append('\n');
            }
            return builder.toString();
        }
        if (result.diagnostics().isEmpty()) {
            return result.status();
        }
        return result.diagnostics().get(0).code() + ": " + result.diagnostics().get(0).message();
    }

    // 作用：格式化方法列表；逻辑：输出总数并逐行打印方法限定名。
    private String methodsText(MethodsResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("Methods: ").append(result.total()).append('\n');
        for (var candidate : result.candidates()) {
            builder.append(candidate.methodQname()).append('\n');
        }
        return builder.toString();
    }

    // 作用：格式化目标列表；逻辑：输出方法、分类统计与分页范围后逐行打印候选。
    private String targetsText(TargetsResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("Method: ").append(result.methodQname()).append('\n');
        builder.append("Targets: ").append(result.total());
        builder.append(" (comparable=").append(result.statistics().comparableCount())
                .append(" fields=").append(result.statistics().fieldCount())
                .append(" roots=").append(result.statistics().objectRootCount())
                .append(" returns=").append(result.statistics().returnCount())
                .append(" calls=").append(result.statistics().callResultCount())
                .append(')');
        if (result.total() > result.targets().size()) {
            builder.append(" (showing ")
                    .append(result.offset() + 1)
                    .append('-')
                    .append(result.offset() + result.targets().size())
                    .append(")");
        }
        builder.append('\n');
        for (var target : result.targets()) {
            builder.append(target.kind()).append('\t').append(target.targetExpression()).append('\n');
        }
        return builder.toString();
    }

    // 作用：格式化追踪结果；逻辑：依次输出索引、方法、节点、边、链、边界、叶子、证据与完整性。
    private String traceText(TraceResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("Index: generation=").append(result.indexGeneration()).append(' ').append(result.indexStatus()).append('\n');
        builder.append("Method: ").append(result.methodQname()).append('\n');
        builder.append("Target: ").append(result.targetExpression()).append('\n');
        builder.append("Direction: ").append(result.direction()).append('\n');
        builder.append("Trace quality: ")
                .append(TraceQuality.from(result, result.allowCandidate(), result.failOnCandidate()).summary())
                .append('\n');
        builder.append("Nodes:").append('\n');
        for (var node : result.nodes()) {
            if (node.internal()) {
                continue;
            }
            builder.append("  ")
                    .append(node.kind())
                    .append('\t')
                    .append(node.displayName())
                    .append('\t')
                    .append(node.scopeQname() == null ? node.scopeId() : node.scopeQname())
                    .append(':')
                    .append(node.lineStart())
                    .append('\n');
        }
        long internalNodes = result.nodes().stream().filter(node -> node.internal()).count();
        if (internalNodes > 0) {
            builder.append("Internal versions: ").append(internalNodes).append('\n');
            result.nodes().stream()
                    .filter(node -> node.internal())
                    .limit(10)
                    .forEach(node -> builder.append("  ")
                            .append(node.kind())
                            .append('\t')
                            .append(node.displayName())
                            .append(':')
                            .append(node.lineStart())
                            .append('\n'));
            appendRemainder(builder, internalNodes, 10, "internal version");
        }
        Map<String, String> nodeLabels = nodeLabels(result);
        Map<String, String> shortLabels = shortLabels(result);
        builder.append("Edges:").append('\n');
        for (var edge : result.edges()) {
            builder.append("  ").append(nodeLabel(nodeLabels, edge.srcId())).append(" -> ").append(nodeLabel(nodeLabels, edge.dstId()))
                    .append('\t').append(edge.edgeType());
            if (edge.operation() != null && !edge.operation().isBlank()) {
                builder.append('\t').append(edge.operation());
            }
            builder.append('\t').append(edge.direction())
                    .append('\t').append("d=").append(edge.depth())
                    .append('\t').append(edge.resolution())
                    .append('\t').append(edge.provenance());
            if (!edge.evidence().isEmpty()) {
                builder.append("\tevidence=").append(edge.evidence().size());
            }
            if (edge.bridged()) {
                builder.append("\tbridged");
            }
            if (edge.argIndex() != null) {
                builder.append("\targ=").append(edge.argIndex());
            }
            builder.append('\n');
        }
        builder.append("Chains: ").append(result.chains().size()).append('\n');
        // Text output is a projection of the authoritative chain list; never
        // silently drop the tail of a large field trace.
        result.chains().forEach(chain -> {
            builder.append("  ")
                    .append(chainDisplay(chain.nodeIds(), shortLabels))
                    .append(" (weak=")
                    .append(chain.weakHops())
                    .append(", hops=")
                    .append(chain.hops().size())
                    .append(")\n");
        });
        builder.append("Boundaries:").append('\n');
        for (var boundary : result.boundaries()) {
            builder.append("  ")
                    .append(boundary.direction())
                    .append('\t')
                    .append(boundary.reason())
                    .append('\t')
                    .append(boundary.reasonCode())
                    .append('\t')
                    .append(boundary.recoverable() ? "recoverable" : "terminal")
                    .append('\t')
                    .append(boundary.suggestedAction())
                    .append('\t')
                    .append(nodeLabel(nodeLabels, boundary.nodeId()));
            if (boundary.sourceFile() != null) {
                builder.append('\t')
                        .append(boundary.sourceFile())
                        .append(':')
                        .append(boundary.lineStart());
            }
            builder.append('\n');
        }
        builder.append("Leaves:").append('\n');
        for (var leaf : result.leaves()) {
            builder.append("  ")
                    .append(leaf.direction())
                    .append('\t')
                    .append(leaf.kind())
                    .append('\t')
                    .append(leaf.expression())
                    .append('\t')
                    .append(leaf.reason());
            if (leaf.sourceFile() != null) {
                builder.append('\t')
                        .append(leaf.sourceFile())
                        .append(':')
                        .append(leaf.lineStart());
            }
            builder.append('\n');
        }
        builder.append("Ambiguous candidates: ").append(result.ambiguousCandidates().size()).append('\n');
        result.ambiguousCandidates().stream().limit(10).forEach(candidate ->
                builder.append("  ")
                        .append(candidate.callerQname() == null ? candidate.callerSymbolId() : candidate.callerQname())
                        .append(" -> ")
                        .append(candidate.methodQname())
                        .append(':')
                        .append(candidate.lineStart())
                        .append('\t')
                        .append(candidate.reason())
                        .append('\t')
                        .append(candidate.reasonCode())
                        .append('\t')
                        .append(candidate.suggestedAction())
                        .append('\n'));
        appendRemainder(builder, result.ambiguousCandidates().size(), 10, "ambiguous candidate");
        builder.append("Weak hops: ").append(result.weakHops().size()).append('\n');
        result.weakHops().forEach(hop ->
                builder.append("  ")
                        .append(hop.callerQname() == null ? hop.callerSymbolId() : hop.callerQname())
                        .append(" -> ")
                        .append(hop.targetExpression())
                        .append(':')
                    .append(hop.lineStart())
                    .append(':')
                    .append(hop.columnStart())
                        .append('\t')
                        .append(hop.reasonCode())
                        .append('\t')
                        .append(hop.suggestedAction())
                        .append('\t')
                        .append(hop.reason())
                        .append('\n'));
        builder.append("Evidence:").append('\n');
        Set<String> evidenceLocations = new LinkedHashSet<>();
        for (var evidence : result.sourceEvidence()) {
            String location = evidence.relativePath() + ":" + evidence.lineStart()
                    + ":" + evidence.columnStart() + ":" + evidence.columnEnd();
            if (evidenceLocations.add(location)) {
                builder.append("  ").append(location);
                if (evidence.expression() != null && !evidence.expression().isBlank()) {
                    builder.append('\t').append(evidence.expression());
                }
                if (evidence.effectLocation() != null && !evidence.effectLocation().isBlank()) {
                    builder.append(" [effect: ").append(evidence.effectLocation()).append(']');
                }
                builder.append('\n');
            }
        }
        builder.append("Completeness: ").append(completeness(result)).append('\n');
        if (result.noMatchReason() != null) {
            builder.append("No match: ")
                    .append(result.noMatchReason().code())
                    .append(" - ")
                    .append(result.noMatchReason().message())
                    .append('\n');
        }
        if (!result.evidenceIssues().isEmpty()) {
            builder.append("Evidence issues: ").append(result.evidenceIssues().size()).append('\n');
            result.evidenceIssues().stream().limit(10).forEach(issue ->
                    builder.append("  ")
                            .append(issue.code())
                            .append('\t')
                            .append(issue.relativePath() == null ? "" : issue.relativePath())
                            .append(':')
                            .append(issue.lineStart())
                            .append('\n'));
        }
        if (!result.truncationReasons().isEmpty()) {
            builder.append("Truncation: ").append(String.join(",", result.truncationReasons())).append('\n');
        }
        if (result.cycleDetected()) {
            builder.append("Diagnostics: CHAIN_CYCLE (graph cycle detected; not a truncation)\n");
        }
        return builder.toString();
    }

    // 作用：判定结果完整性；逻辑：委托完整性模型，把弱跳与未桥接消费者计入结论。
    private String completeness(TraceResult result) {
        return TraceCompleteness.from(result).summary();
    }

    // 作用：提示被折叠的剩余条目；逻辑：总数超过展示上限时追加一行明确数量，避免静默截断。
    private void appendRemainder(StringBuilder builder, long total, int shown, String label) {
        if (total > shown) {
            builder.append("  ... ").append(total - shown).append(" more ").append(label).append("(s)\n");
        }
    }

    // 作用：构建节点完整标签；逻辑：拼接类型、展示名、作用域与行号。
    private Map<String, String> nodeLabels(TraceResult result) {
        Map<String, String> labels = new HashMap<>();
        for (var node : result.nodes()) {
            labels.put(
                    node.nodeId(),
                    node.kind() + ":" + node.displayName()
                            + (node.scopeQname() == null ? "" : " @ " + node.scopeQname())
                            + ":" + node.lineStart()
            );
        }
        return labels;
    }

    // 作用：构建节点短标签；逻辑：使用展示名与简短作用域名便于阅读链路。
    private Map<String, String> shortLabels(TraceResult result) {
        Map<String, String> labels = new HashMap<>();
        for (var node : result.nodes()) {
            labels.put(
                    node.nodeId(),
                    node.displayName() + " @ " + shortScope(node.scopeQname()) + ":" + node.lineStart()
            );
        }
        return labels;
    }

    // 作用：缩短作用域名；逻辑：保留简单类名与方法名，去掉包名与参数列表。
    private String shortScope(String scopeQname) {
        if (scopeQname == null || scopeQname.isBlank()) {
            return "?";
        }
        int hash = scopeQname.indexOf('#');
        if (hash < 0) {
            int dot = scopeQname.lastIndexOf('.');
            return dot < 0 ? scopeQname : scopeQname.substring(dot + 1);
        }
        String type = scopeQname.substring(0, hash);
        int dot = type.lastIndexOf('.');
        String simpleType = dot < 0 ? type : type.substring(dot + 1);
        String method = scopeQname.substring(hash + 1);
        int paren = method.indexOf('(');
        return simpleType + "#" + (paren < 0 ? method : method.substring(0, paren));
    }

    // 作用：格式化链路；逻辑：最多展示八个节点，超出部分给出剩余数量并限制总长度。
    private String chainDisplay(List<String> nodeIds, Map<String, String> labels) {
        StringBuilder builder = new StringBuilder();
        int limit = Math.min(nodeIds.size(), 8);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                builder.append(" -> ");
            }
            builder.append(labels.getOrDefault(nodeIds.get(i), "UNKNOWN_NODE"));
        }
        if (nodeIds.size() > limit) {
            builder.append(" -> ... (")
                    .append(nodeIds.size() - limit)
                    .append(" more)");
        }
        String text = builder.toString();
        return text.length() <= 500 ? text : text.substring(0, 497) + "...";
    }

    // 作用：取节点标签；逻辑：缺失时回退为未知节点占位。
    private String nodeLabel(Map<String, String> nodeLabels, String nodeId) {
        return nodeLabels.getOrDefault(nodeId, "UNKNOWN_NODE");
    }

    // 作用：格式化索引状态；逻辑：输出计数、符号构成与可选的精确率、边界密度和失败尝试信息。
    private String statusText(IndexStatusResult status) {
        StringBuilder builder = new StringBuilder();
        builder.append("Index: generation=").append(status.indexGeneration())
                .append(" status=").append(status.indexStatus())
                .append(" freshness=").append(status.freshness())
                .append(" files=").append(status.sourceFileCount())
                .append(" symbols=").append(status.symbolCount())
                .append(" types=").append(status.symbolStatistics().typeCount())
                .append(" methods=").append(status.symbolStatistics().methodCount())
                .append(" businessMethods=").append(status.symbolStatistics().businessMethodCount())
                .append(" accessors=").append(status.symbolStatistics().accessorCount())
                .append(" constructors=").append(status.symbolStatistics().constructorCount())
                .append(" initializers=").append(status.symbolStatistics().initializerCount())
                .append(" nodes=").append(status.valueNodeCount())
                .append(" edges=").append(status.dataEdgeCount())
                .append(" calls=").append(status.callEdgeCount())
                .append(" pending=").append(status.pendingRefCount());
        if (status.quality() != null) {
            builder.append('\n')
                    .append("Quality: exactRatio=")
                    .append(percent(status.quality().exactRatio()))
                    .append(" candidateRatio=")
                    .append(percent(status.quality().candidateRatio()))
                    .append(" unresolvedRatio=")
                    .append(percent(status.quality().unresolvedRatio()))
                    .append(" exact=").append(status.quality().exactEdgeCount())
                    .append(" candidate=").append(status.quality().candidateEdgeCount())
                    .append(" unresolved=").append(status.quality().unresolvedEdgeCount())
                    .append(" runtimeObserved=").append(status.quality().runtimeObservedEdgeCount())
                    .append(" openBoundaries=").append(status.quality().openBoundaryCount())
                    .append(" boundaryDensityPer1000Methods=")
                    .append(String.format(java.util.Locale.ROOT, "%.1f",
                            status.quality().openBoundaryDensityPer1000Methods()));
            if (!status.quality().topBoundaryReasons().isEmpty()) {
                builder.append(" topBoundaries=");
                builder.append(status.quality().topBoundaryReasons().stream()
                        .map(reason -> reason.reason() + ":" + reason.count())
                        .collect(java.util.stream.Collectors.joining(",")));
            }
        }
        if (status.lastAttemptStatus() != null && !"IDLE".equals(status.lastAttemptStatus()) && !"SUCCEEDED".equals(status.lastAttemptStatus())) {
            builder.append(" lastAttempt=").append(status.lastAttemptStatus());
            if (status.lastErrorCode() != null) {
                builder.append(" error=").append(status.lastErrorCode());
            }
            if (status.lastErrorMessage() != null && !status.lastErrorMessage().isBlank()) {
                builder.append(" message=").append(status.lastErrorMessage());
            }
        }
        return builder.toString();
    }

    // 作用：格式化百分比；逻辑：按一位小数输出百分数。
    private String percent(double ratio) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", ratio * 100d);
    }

    // 作用：格式化环境体检；逻辑：逐行输出 Java、编译器、SQLite、系统与索引文件状态。
    private String doctorText(DoctorResult doctor) {
        return "Java: " + doctor.javaVersion()
                + "\nCompiler: " + (doctor.compilerAvailable() ? "available" : "missing")
                + "\nSQLite: " + (doctor.sqliteAvailable() ? "available" : "missing")
                + "\nOS: " + doctor.operatingSystem()
                + "\nArchitecture: " + doctor.architecture()
                + "\nSQLite file: " + (doctor.sqliteFileExists() ? "present" : "absent");
    }
}
