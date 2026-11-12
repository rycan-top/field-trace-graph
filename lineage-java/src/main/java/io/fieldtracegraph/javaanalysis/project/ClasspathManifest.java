package io.fieldtracegraph.javaanalysis.project;

import java.util.List;

// 作用：记录一份 classpath 清单。
// 使用场景：承载多个 classpath 条目并参与索引上下文计算。

/**
 * Declared local dependency entries read from a project classpath manifest.
 * This is analysis input only; the tool never downloads or resolves entries.
 */
public record ClasspathManifest(List<ClasspathEntry> entries) {
    // 作用：创建空清单；逻辑：返回条目列表为空且不可变的实例。
    public static ClasspathManifest empty() {
        return new ClasspathManifest(List.of());
    }
}
