package io.fieldtracegraph.core.model;

// 作用：方法参数被修改的记录。
// 使用场景：跨调用值流判断实参是否被写回，从而决定是否继续向上追踪。

/**
 * A callee-side effect on one callable parameter. The target path is relative
 * to the parameter root, for example {@code [*].recommendTabWaveImg}.
 *
 */
public record ParameterMutationRecord(
        int parameterIndex,
        String parameterName,
        String targetPath,
        String operation,
        int lineStart,
        int columnStart,
        int lineEnd,
        int columnEnd,
        /*
         * File that owns the recorded line. Forwarded mutations keep the
         * originating callee's position, so the immediate callee's file is
         * wrong for effect= evidence. Null for pre-0.1.18 snapshots.
         */
        String fileId
) {
    public ParameterMutationRecord(
            int parameterIndex,
            String parameterName,
            String targetPath,
            String operation,
            int lineStart,
            int columnStart,
            int lineEnd,
            int columnEnd
    ) {
        this(parameterIndex, parameterName, targetPath, operation,
                lineStart, columnStart, lineEnd, columnEnd, null);
    }
}
