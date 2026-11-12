package io.fieldtracegraph.javaanalysis.semantic.protocol;

// 作用：描述一个传给语义 worker 的源码单元。
// 使用场景：编译分析的输入数据。

/**
 * A single compilation unit sent to the isolated semantic worker. The parent
 * process derives the class name from the parsed AST so the worker never needs
 * JavaParser and can rely on javac alone.
 */
public record SemanticSource(
        String className,
        String relativePath,
        String content
) {
}
