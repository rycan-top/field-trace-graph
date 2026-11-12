package io.fieldtracegraph.core.model;

import java.util.List;

// 作用：源码扫描选项集合。
// 使用场景：统一传递语言级别、是否包含测试、生成源码根与 classpath。

/**
 * Per-command source scanning overrides. A null language level means the
 * module build configuration decides; includeTests toggles test source roots.
 * Generated source roots are explicit: the tool never guesses annotation
 * processor output locations. Classpath entries are explicit too; a directory
 * or archive is accepted and participates in the index context fingerprint.
 */
public record SourceScanOptions(
        String languageLevelOverride,
        boolean includeTests,
        List<String> generatedSourceRoots,
        List<String> classpathEntries
) {
    // 作用：规范化生成源码与 classpath 列表；逻辑：过滤空值、去空格、去重后转为不可变列表。
    public SourceScanOptions {
        generatedSourceRoots = generatedSourceRoots == null
                ? List.of()
                : generatedSourceRoots.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
        classpathEntries = classpathEntries == null
                ? List.of()
                : classpathEntries.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
    }

    // 作用：兼容两参数调用；逻辑：生成源码与 classpath 都默认为空。
    public SourceScanOptions(String languageLevelOverride, boolean includeTests) {
        this(languageLevelOverride, includeTests, List.of(), List.of());
    }

    // 作用：返回默认扫描选项；逻辑：不覆盖语言级别、排除测试、生成源码与 classpath 均为空。
    public static SourceScanOptions defaults() {
        return new SourceScanOptions(null, false, List.of(), List.of());
    }
}
