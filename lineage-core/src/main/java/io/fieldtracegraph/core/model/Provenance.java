package io.fieldtracegraph.core.model;

import java.util.Collection;
import java.util.Locale;

// 作用：证据来源枚举。
// 使用场景：区分源码、生成源码、字节码、框架适配、运行时追踪与人工契约。

/**
 * Where an edge came from. Multiple provenances may describe the same logical
 * edge; this enum only names the strongest evidence selected for display.
 *
 *
 * 作用：证据来源枚举；场景：源码/生成源码/字节码/框架适配/运行时/人工契约。
 */
public enum Provenance {
    SOURCE,
    GENERATED_SOURCE,
    BYTECODE,
    FRAMEWORK_ADAPTER,
    RUNTIME_TRACE,
    MANUAL_CONTRACT;

    // 作用：解析证据来源字符串；逻辑：空值或未识别值回退 SOURCE，先归一化再按名称匹配。
    public static Provenance from(String value) {
        if (value == null || value.isBlank()) {
            return SOURCE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SOURCE;
        }
    }

    // 作用：从多份证据中选出最强来源；逻辑：按优先级取最高值，空集合回退 SOURCE。
    public static Provenance aggregate(Collection<Provenance> provenances) {
        Provenance selected = SOURCE;
        int score = -1;
        for (Provenance provenance : provenances) {
            int candidate = priority(provenance);
            if (candidate > score) {
                selected = provenance;
                score = candidate;
            }
        }
        return selected;
    }

    // 作用：定义证据来源的强弱顺序；逻辑：运行时高于人工契约，其次字节码、生成源码、源码与框架适配。
    private static int priority(Provenance provenance) {
        return switch (provenance) {
            case RUNTIME_TRACE -> 6;
            case MANUAL_CONTRACT -> 5;
            case BYTECODE -> 4;
            case GENERATED_SOURCE -> 3;
            case SOURCE -> 2;
            case FRAMEWORK_ADAPTER -> 1;
        };
    }
}
