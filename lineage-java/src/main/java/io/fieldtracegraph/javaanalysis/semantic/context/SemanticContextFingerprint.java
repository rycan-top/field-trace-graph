package io.fieldtracegraph.javaanalysis.semantic.context;

import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.project.ClasspathEntryValidator;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

// 作用：计算语义上下文指纹并维护分析规则版本。
// 使用场景：判断索引或缓存是否失效，规则变更时必须提升版本号。

public final class SemanticContextFingerprint {
    /**
     * Bumped whenever extraction rules change so cached and incremental
     * indexes are invalidated instead of silently mixing old and new evidence.
     * 0.3.3: same-class helper calls used as control conditions are retained in
     * the call graph, so the fields they read stay reachable.
     * 0.3.4: unresolved calls are stored only in {@code pending_parse_invoke_ref}.
     * 0.4.0: edge provenance and implicit generated-source publication.
     * 0.4.1: explicit classpath metadata and actionable external boundaries.
     * 0.4.2: platform-library boundaries and receiver-qualified fallbacks.
     * 0.4.3: precise missing-callee, generated-member and receiver reasons.
     * 0.4.4: fully-qualified type roots and serialization method names.
     * 0.4.5: super-constructor and static external type classification.
     * 0.4.6: separate accessor-generating annotations from constructor-only ones.
     * 0.4.7: do not invent zero-argument generated constructors.
     * 0.4.8: qualified supertypes, Object methods and inherited external members.
     * 0.4.9: implicit java.lang.Object superclass.
     * 0.4.10: inherited-field and chained inherited-call classification.
     * 0.4.11: transitive external-supertype detection.
     * 0.4.12: keep static type receivers and narrow Dubbo RPC prefixes.
     * 0.5.3: vendor-neutral RPC boundaries (Dubbo/gRPC/Thrift/CXF/JAX-WS) and
     * open-source telemetry markers (Micrometer/OpenTelemetry/Prometheus).
     * 0.5.4: exclude serializer calls nested in logging/diagnostic calls.
     * 0.5.5: bridge every source implementation of an abstract/RPC call and
     *        preserve object-valued property hand-offs.
     */
    public static final String RULE_VERSION = "0.5.5";

    // 作用：按显式 classpath 计算上下文指纹；逻辑：先把命令行条目纳入校验指纹，再转交核心计算。
    public String compute(
            List<SourceFileRecord> files,
            String parserVersion,
            Path projectRoot,
            boolean includeTests,
            List<String> explicitClasspath
    ) {
        return compute(
                files,
                parserVersion,
                new ClasspathEntryValidator().fingerprint(projectRoot, explicitClasspath),
                includeTests
        );
    }

    // 作用：汇总语义分析上下文指纹；逻辑：组合模块语言级别、解析器与规则版本、classpath、编码及测试范围后取哈希。
    public String compute(List<SourceFileRecord> files, String parserVersion, String classpathFingerprint, boolean includeTests) {
        String moduleLevels = files.stream()
                .map(file -> file.moduleId() + "\u001f" + file.languageLevel())
                .distinct()
                .sorted()
                .collect(Collectors.joining("\u001e"));
        return Ids.sha256(
                moduleLevels,
                parserVersion,
                RULE_VERSION,
                classpathFingerprint == null ? "" : classpathFingerprint,
                "encoding=UTF-8",
                includeTests ? "tests=included" : "tests=excluded"
        );
    }
}
