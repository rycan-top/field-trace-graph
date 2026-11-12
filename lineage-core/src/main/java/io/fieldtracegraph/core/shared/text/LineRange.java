package io.fieldtracegraph.core.shared.text;

// 作用：行号区间工具。
// 使用场景：源码证据定位与结果中的位置展示。

/**
 * Immutable one-based line range value object. It validates the range so
 * source-evidence readers reject inverted or empty ranges before touching a
 * file.
 */
public record LineRange(int startLine, int endLine) {
    // 作用：校验行号区间；逻辑：起始行必须为正，结束行不得早于起始行。
    public LineRange {
        if (startLine < 1) {
            throw new IllegalArgumentException("start line must be positive");
        }
        if (endLine < startLine) {
            throw new IllegalArgumentException("end line must not precede start line");
        }
    }

    // 作用：创建行号区间；逻辑：直接委托主构造器完成校验。
    public static LineRange of(int startLine, int endLine) {
        return new LineRange(startLine, endLine);
    }

    // 作用：计算区间包含的行数；逻辑：结束行减起始行再加一。
    public int lineCount() {
        return endLine - startLine + 1;
    }
}
