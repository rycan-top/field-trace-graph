package io.fieldtracegraph.javaanalysis.semantic.protocol;

import java.util.List;

// 作用：记录一次调用点的归属结果。
// 使用场景：语义 worker 返回被调方法、位置与解析状态。

/**
 * A compile-time resolved call target produced by javac. The correlation key is
 * based on the source start position plus the call shape. The exact executable
 * parameter types are kept separately so overloads with the same arity are not
 * collapsed back to an ambiguous candidate.
 */
public record CallAttribution(
        String relativePath,
        int line,
        int column,
        String methodName,
        int argumentCount,
        String declaringTypeQname,
        List<String> parameterTypes,
        boolean varargs
) {
    // 作用：生成调用归属关联键；逻辑：拼接路径、行列、方法名与实参数量，保留重载区分所需的完整位置。
    public String key() {
        return relativePath + ":" + line + ":" + column + ":"
                + methodName + ":" + argumentCount;
    }

    // 作用：生成兼容旧格式的关联键；逻辑：拼接路径、行号、方法名与实参数量，不包含列号。
    public String legacyKey() {
        return relativePath + ":" + line + ":" + methodName + ":" + argumentCount;
    }
}
