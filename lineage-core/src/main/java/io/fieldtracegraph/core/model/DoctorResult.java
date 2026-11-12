package io.fieldtracegraph.core.model;

// 作用：运行环境体检结果。
// 使用场景：doctor 命令检查 JDK、权限、配置后输出。

public record DoctorResult(
        String javaVersion,
        String javaVendor,
        String javaHome,
        boolean compilerAvailable,
        boolean sqliteAvailable,
        String operatingSystem,
        String architecture,
        String workingDirectory,
        boolean sqliteFileExists
) {
}
