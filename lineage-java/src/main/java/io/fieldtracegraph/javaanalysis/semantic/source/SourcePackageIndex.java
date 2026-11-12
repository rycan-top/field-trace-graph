package io.fieldtracegraph.javaanalysis.semantic.source;

import java.util.Map;

// 作用：维护源码包索引。
// 使用场景：解析同包类与包级可见性。

/**
 * Immutable mapping from a source file to its declared package. It does not
 * perform inheritance resolution; {@code SourceTypeCatalog} owns that concern.
 */
public final class SourcePackageIndex {
    private final Map<String, String> packageByRelativePath;

    // 作用：保存源码路径到包名的映射；逻辑：直接持有构建阶段传入的不可变映射。
    SourcePackageIndex(Map<String, String> packageByRelativePath) {
        this.packageByRelativePath = packageByRelativePath;
    }

    // 作用：查询源码文件所属包；逻辑：按相对路径取值，未知文件返回空包名。
    public String packageOf(String relativePath) {
        return packageByRelativePath.getOrDefault(relativePath, "");
    }
}
