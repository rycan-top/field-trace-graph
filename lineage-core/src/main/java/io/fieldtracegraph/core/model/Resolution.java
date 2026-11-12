package io.fieldtracegraph.core.model;

import java.util.Collection;
import java.util.Locale;

// 作用：解析强度枚举，取值包括精确静态、候选、未解析与运行时观测。
// 使用场景：边、边界、质量统计与 JSON 输出中的置信度表达。

/**
 * How strongly the graph proves one logical field edge.
 *
 * <p>Resolution is deliberately independent from provenance: a candidate edge
 * can come from source code or a framework adapter, while runtime observation
 * may confirm or replace that candidate without deleting the original
 * evidence.</p>
 *
 * 作用：解析强度枚举；    场景：边、边界、质量统计、JSON 输出。
 */
public enum Resolution {
    EXACT_STATIC,
    CANDIDATE,
    UNRESOLVED,
    RUNTIME_OBSERVED;

    // 作用：解析解析强度字符串；逻辑：空值或未识别值回退 UNRESOLVED，先归一化再按名称匹配。
    public static Resolution from(String value) {
        if (value == null || value.isBlank()) {
            return UNRESOLVED;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "EXACT_STATIC", "COMPILE_TIME_TARGET", "VIRTUAL_UNIQUE" -> EXACT_STATIC;
            case "CANDIDATE" -> CANDIDATE;
            case "RUNTIME_OBSERVED" -> RUNTIME_OBSERVED;
            default -> UNRESOLVED;
        };
    }

    // 作用：聚合多条证据的解析强度；逻辑：按精确度取最高值，空集合回退 UNRESOLVED。
    public static Resolution aggregate(Collection<Resolution> resolutions) {
        boolean exact = false;
        boolean candidate = false;
        boolean unresolved = false;
        for (Resolution resolution : resolutions) {
            if (resolution == RUNTIME_OBSERVED) {
                return RUNTIME_OBSERVED;
            }
            exact |= resolution == EXACT_STATIC;
            candidate |= resolution == CANDIDATE;
            unresolved |= resolution == UNRESOLVED;
        }
        if (exact) {
            return EXACT_STATIC;
        }
        if (candidate) {
            return CANDIDATE;
        }
        return UNRESOLVED;
    }

    // 作用：判断是否为精确静态解析；逻辑：只有 EXACT_STATIC 返回 true。
    public boolean exact() {
        return this == EXACT_STATIC || this == RUNTIME_OBSERVED;
    }
}
