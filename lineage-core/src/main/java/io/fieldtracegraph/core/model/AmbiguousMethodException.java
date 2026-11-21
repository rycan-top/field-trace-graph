package io.fieldtracegraph.core.model;

import java.util.List;
import java.util.stream.Collectors;

// 作用：表示方法选择器命中了多个符号。
// 使用场景：trace / trace-batch 在重复类名或重复源码根下拒绝猜测，并把候选返回给调用方。

public final class AmbiguousMethodException extends IllegalArgumentException {
    private final transient List<MethodCandidate> candidates;

    // 作用：记录歧义候选；逻辑：把候选模块与签名拼进异常消息，便于直接定位重复源码。
    public AmbiguousMethodException(List<MethodCandidate> candidates) {
        super("METHOD_AMBIGUOUS: " + candidates.size() + " candidates -> "
                + candidates.stream()
                .map(candidate -> candidate.moduleId() + "::" + candidate.methodQname())
                .distinct()
                .collect(Collectors.joining(" | ")));
        this.candidates = List.copyOf(candidates);
    }

    // 作用：返回全部候选；逻辑：直接给出不可变列表。
    public List<MethodCandidate> candidates() {
        return candidates;
    }
}
