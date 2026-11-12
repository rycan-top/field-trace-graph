package io.fieldtracegraph.javaanalysis.semantic.cache;

import io.fieldtracegraph.javaanalysis.semantic.protocol.CallAttribution;

import java.util.LinkedHashMap;
import java.util.Map;

// 作用：缓存 javac 语义分析得到的调用归属结果。
// 使用场景：多次查询同一调用点时复用结果，减少重复计算。

/**
 * Bounded, attempt-scoped cache for semantic call attributions. It is never
 * shared across index attempts.
 */
public final class SemanticAnalysisCache {
    private static final int MAX_ENTRIES = 100_000;

    private final Map<String, CallAttribution> entries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        // 作用：控制缓存容量；逻辑：条目数超过上限时淘汰最久未使用的记录。
        protected boolean removeEldestEntry(Map.Entry<String, CallAttribution> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    // 作用：写入调用归属缓存；逻辑：按键覆盖或新增，并触发访问顺序更新。
    public void put(String key, CallAttribution attribution) {
        entries.put(key, attribution);
    }

    // 作用：读取调用归属缓存；逻辑：按键取值并刷新最近使用顺序，未命中返回空。
    public CallAttribution get(String key) {
        return entries.get(key);
    }

    // 作用：导出缓存快照；逻辑：复制当前映射，避免调用方影响缓存内部状态。
    public Map<String, CallAttribution> snapshot() {
        return Map.copyOf(entries);
    }
}
