package io.fieldtracegraph.javaanalysis.semantic.source;

import io.fieldtracegraph.javaanalysis.semantic.protocol.SemanticSource;

import java.util.List;

// 作用：承载一组待编译源码。
// 使用场景：作为语义分析的输入集合。

public record CompilationSourceSet(List<SemanticSource> sources) {
}
