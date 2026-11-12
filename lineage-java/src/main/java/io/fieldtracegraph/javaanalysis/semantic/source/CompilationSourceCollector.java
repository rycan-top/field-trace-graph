package io.fieldtracegraph.javaanalysis.semantic.source;

import com.github.javaparser.ast.CompilationUnit;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.javaanalysis.semantic.protocol.SemanticSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 作用：收集待编译的源码单元。
// 使用场景：把解析结果转换为语义 worker 的输入。

/**
 * Collects the bounded source set handed to the isolated javac worker. It only
 * selects top-level types and never reads the filesystem itself.
 */
public final class CompilationSourceCollector {
    // 作用：收集交给语义 worker 的源码；逻辑：为每个编译单元取首个顶层类型全名，并连同路径与内容打包。
    public CompilationSourceSet collect(Map<SourceFileRecord, CompilationUnit> units) {
        List<SemanticSource> sources = new ArrayList<>();
        for (var entry : units.entrySet()) {
            String className = entry.getValue().getTypes().stream().findFirst()
                    .map(type -> type.getFullyQualifiedName().orElse(type.getNameAsString()))
                    .orElse(null);
            if (className != null) {
                sources.add(new SemanticSource(className, entry.getKey().relativePath(), entry.getKey().content()));
            }
        }
        return new CompilationSourceSet(List.copyOf(sources));
    }
}
