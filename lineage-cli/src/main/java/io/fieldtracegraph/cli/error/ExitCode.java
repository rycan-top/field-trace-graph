package io.fieldtracegraph.cli.error;

// 作用：定义进程退出码常量。
// 使用场景：脚本据此判断成功、用法错误、索引过期或质量门禁失败。

public final class ExitCode {
    public static final int OK = 0;
    public static final int USAGE = 2;
    public static final int STALE = 3;
    public static final int INTERNAL = 4;
    public static final int RESOURCE = 5;
    /** A quality gate (boundary density, reason code, weak hop budget) failed. */
    public static final int QUALITY_GATE = 6;

    // 作用：阻止常量类被实例化；逻辑：私有空构造器。
    private ExitCode() {
    }
}
