package io.fieldtracegraph.javaanalysis.resolution;

import java.util.List;

// 作用：记录一次调用解析的结果。
// 使用场景：把调用点映射到目标方法及重载候选。

public record CallResolution(
        String resolution,
        String calleeSymbolId,
        String targetQname,
        List<String> candidateQnames,
        String reason,
        List<Target> targets
) {
    // 作用：描述一个可切片的调用目标；逻辑：保存符号与全名，供多实现虚调用逐条建边。
    public record Target(String symbolId, String qname) {
    }

    // 作用：创建编译期已确定目标的解析结果；逻辑：使用 COMPILE_TIME_TARGET 状态且不带候选列表。
    public static CallResolution compileTimeTarget(String calleeSymbolId, String targetQname) {
        return new CallResolution(
                "COMPILE_TIME_TARGET", calleeSymbolId, targetQname, List.of(), null, List.of());
    }

    // 作用：创建精确静态调用结果；逻辑：使用 EXACT_STATIC 状态且不带候选列表。
    public static CallResolution exactStatic(String calleeSymbolId, String targetQname) {
        return new CallResolution(
                "EXACT_STATIC", calleeSymbolId, targetQname, List.of(), null, List.of());
    }

    /**
     * A virtual call whose declared type is abstract and whose source set
     * contains exactly one concrete override.
     */
    // 作用：创建唯一虚调用结果；逻辑：使用 VIRTUAL_UNIQUE 状态，表示抽象声明在源码中只有一个具体实现。
    public static CallResolution virtualUnique(String calleeSymbolId, String targetQname) {
        return new CallResolution(
                "VIRTUAL_UNIQUE", calleeSymbolId, targetQname, List.of(), null, List.of());
    }

    /**
     * A virtual call declared on an interface or abstract class with several
     * source implementations. Every implementation is a possible runtime
     * receiver, so the call must stay ambiguous but still expose the concrete
     * method bodies that can carry the argument and return values.
     */
    // 作用：创建多实现虚调用结果；逻辑：保留全部候选目标与其符号，供调用点逐条生成候选桥接边。
    public static CallResolution virtualAmbiguous(List<Target> targets) {
        List<Target> immutable = List.copyOf(targets);
        return new CallResolution(
                "VIRTUAL_AMBIGUOUS",
                null,
                immutable.isEmpty() ? "<virtual>" : immutable.get(0).qname(),
                immutable.stream().map(Target::qname).toList(),
                "VIRTUAL_AMBIGUOUS",
                immutable
        );
    }

    // 作用：创建单个候选目标的调用结果；逻辑：使用 CANDIDATE 状态但保留具体符号，使参数与返回值可以桥接。
    public static CallResolution candidateTarget(String calleeSymbolId, String targetQname) {
        return new CallResolution(
                "CANDIDATE", calleeSymbolId, targetQname, List.of(), "VIRTUAL_AMBIGUOUS", List.of());
    }

    // 作用：把当前结果降级为精确静态结果；逻辑：保留目标与候选信息，仅替换解析状态。
    public CallResolution asExactStatic() {
        return new CallResolution("EXACT_STATIC", calleeSymbolId, targetQname, candidateQnames, reason, targets);
    }

    // 作用：创建候选调用结果；逻辑：标记 CANDIDATE 并记录重载歧义原因与候选目标。
    public static CallResolution candidate(String targetQname, List<String> candidateQnames) {
        return new CallResolution(
                "CANDIDATE", null, targetQname, List.copyOf(candidateQnames), "OVERLOAD_AMBIGUOUS", List.of());
    }

    // 作用：创建未解析调用结果；逻辑：使用 UNRESOLVED 状态并保留目标名与失败原因。
    public static CallResolution unresolved(String targetQname, String reason) {
        return new CallResolution("UNRESOLVED", null, targetQname, List.of(), reason, List.of());
    }

    // 作用：判断是否携带逐目标桥接信息；逻辑：目标列表非空时返回真。
    public boolean hasConcreteTargets() {
        return !targets.isEmpty();
    }
}
