package io.fieldtracegraph.core.model;

// 作用：开放边界的建议动作枚举。
// 使用场景：把边界原因翻译成补 classpath、跑注解处理器、导入运行时证据等可执行动作。

/**
 * Structured, actionable explanation for an unresolved boundary. The action
 * names the next evidence-producing step instead of treating uncertainty as a
 * generic failure.
 **/
public record BoundaryAction(
        String reasonCode,
        boolean recoverable,
        String suggestedAction,
        String evidenceSource
) {
    // 作用：把待解析引用的原因映射为建议动作；逻辑：按原因码查表，未识别时回退到通用动作。
    public static BoundaryAction forPendingReason(String reason) {
        BoundaryReason reasonCode = BoundaryReason.from(reason);
        return new BoundaryAction(
                reasonCode.name(),
                reasonCode.recoverable(),
                reasonCode.suggestedAction(),
                evidenceSource(reasonCode)
        );
    }

    // 作用：确定某类原因对应的证据来源；逻辑：按原因码归类到源码、字节码、运行时或人工契约。
    private static String evidenceSource(BoundaryReason reason) {
        return switch (reason) {
            case RPC_BOUNDARY, SERIALIZATION_BOUNDARY, RPC_SERIALIZATION, AOP,
                    DYNAMIC_PROXY, DYNAMIC_SQL -> Provenance.FRAMEWORK_ADAPTER.name();
            case PLATFORM_LIBRARY, EXTERNAL_LIBRARY -> Provenance.BYTECODE.name();
            case GENERATED_CODE_MISSING, CALLEE_SYMBOL_MISSING -> Provenance.GENERATED_SOURCE.name();
            default -> Provenance.SOURCE.name();
        };
    }
}
