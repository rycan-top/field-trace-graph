package io.fieldtracegraph.javaanalysis.semantic.source;

import com.github.javaparser.ast.CompilationUnit;
import io.fieldtracegraph.core.model.SourceFileRecord;

import java.util.HashMap;
import java.util.Map;

// 作用：构建源码包索引。
// 使用场景：分析开始时按包组织全部编译单元。

/**
 * Builds an immutable package index from parsed compilation units. It reads
 * only package declarations and never touches the filesystem again.
 */
public final class SourcePackageIndexBuilder {
    // 作用：构建源码包索引；逻辑：读取每个编译单元的包声明，按相对路径登记后复制为不可变索引。
    public SourcePackageIndex build(Map<SourceFileRecord, CompilationUnit> units) {
        Map<String, String> packages = new HashMap<>();
        for (var entry : units.entrySet()) {
            String packageName = entry.getValue().getPackageDeclaration()
                    .map(declaration -> declaration.getNameAsString())
                    .orElse("");
            packages.put(entry.getKey().relativePath(), packageName);
        }
        return new SourcePackageIndex(Map.copyOf(packages));
    }
}
