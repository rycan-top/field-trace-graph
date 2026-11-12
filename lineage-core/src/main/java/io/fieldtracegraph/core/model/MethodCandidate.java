package io.fieldtracegraph.core.model;

// 作用：方法搜索的单个候选。
// 使用场景：methods 命令输出方法签名、位置与摘要。

public record MethodCandidate(
        String methodId,
        String methodQname,
        String moduleId,
        String kind,
        String simpleName,
        String declaringType,
        String signature
) {
}
