package io.fieldtracegraph.javaanalysis.resolution;

import java.util.List;

// 作用：记录一次调用解析的结果。
// 使用场景：把调用点映射到目标方法及重载候选。

public record CallResolution(
        String resolution,
        String calleeSymbolId,
        String targetQname,
        List<String> candidateQnames,
        String reason
) {
    // 作用：创建编译期已确定目标的解析结果；逻辑：使用 COMPILE_TIME_TARGET 状态且不带候选列表。
    public static CallResolution compileTimeTarget(String calleeSymbolId, String targetQname) {
        return new CallResolution("COMPILE_TIME_TARGET", calleeSymbolId, targetQname, List.of(), null);
    }

    // 作用：创建精确静态调用结果；逻辑：使用 EXACT_STATIC 状态且不带候选列表。
    public static CallResolution exactStatic(String calleeSymbolId, String targetQname) {
        return new CallResolution("EXACT_STATIC", calleeSymbolId, targetQname, List.of(), null);
    }

    /**
     * A virtual call whose declared type is abstract and whose source set
     * contains exactly one concrete override.
     */
    // 作用：创建唯一虚调用结果；逻辑：使用 VIRTUAL_UNIQUE 状态，表示抽象声明在源码中只有一个具体实现。
    public static CallResolution virtualUnique(String calleeSymbolId, String targetQname) {
        return new CallResolution("VIRTUAL_UNIQUE", calleeSymbolId, targetQname, List.of(), null);
    }

    // 作用：把当前结果降级为精确静态结果；逻辑：保留目标与候选信息，仅替换解析状态。
    public CallResolution asExactStatic() {
        return new CallResolution("EXACT_STATIC", calleeSymbolId, targetQname, candidateQnames, reason);
    }

    // 作用：创建候选调用结果；逻辑：标记 CANDIDATE 并记录重载歧义原因与候选目标。
    public static CallResolution candidate(String targetQname, List<String> candidateQnames) {
        return new CallResolution("CANDIDATE", null, targetQname, List.copyOf(candidateQnames), "OVERLOAD_AMBIGUOUS");
    }

    // 作用：创建未解析调用结果；逻辑：使用 UNRESOLVED 状态并保留目标名与失败原因。
    public static CallResolution unresolved(String targetQname, String reason) {
        return new CallResolution("UNRESOLVED", null, targetQname, List.of(), reason);
    }
}
