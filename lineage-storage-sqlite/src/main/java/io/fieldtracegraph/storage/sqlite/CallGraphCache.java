package io.fieldtracegraph.storage.sqlite;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

// 作用：缓存只读快照的调用图聚合结果。
// 使用场景：trace-batch 在同一进程内执行数千条用例时，避免每条用例全表扫描调用边。

/**
 * The caller index and the unresolved-reference index are generation-wide
 * aggregates. Rebuilding them for every case scans the whole
 * {@code parsed_invoke_edge} table (hundreds of thousands of rows in large
 * repositories), which dominates single-trace latency. They only change when a
 * new generation is published, so one snapshot per (database, generation) is
 * both safe and sufficient.
 */
final class CallGraphCache {
    private static final Object LOCK = new Object();
    private static Entry cached;

    // 作用：阻止实例化；逻辑：私有空构造器。
    private CallGraphCache() {
    }

    private static final class Entry {
        private final String path;
        private final int generation;
        private Map<String, Set<String>> callers;
        private Map<String, Set<String>> pendingRefs;

        private Entry(String path, int generation) {
            this.path = path;
            this.generation = generation;
        }
    }

    // 作用：读取被调方到调用方的映射；逻辑：命中同一库同一代时复用，否则重建并替换整代缓存。
    static Map<String, Set<String>> callers(
            String databasePath,
            int generation,
            Supplier<Map<String, Set<String>>> loader
    ) {
        if (databasePath == null) {
            return loader.get();
        }
        synchronized (LOCK) {
            Entry entry = entry(databasePath, generation);
            if (entry.callers == null) {
                entry.callers = loader.get();
            }
            return entry.callers;
        }
    }

    // 作用：读取未解析引用到调用方的映射；逻辑：与调用图同代缓存，首次访问时惰性加载。
    static Map<String, Set<String>> pendingRefsByReferenceName(
            String databasePath,
            int generation,
            Supplier<Map<String, Set<String>>> loader
    ) {
        if (databasePath == null) {
            return loader.get();
        }
        synchronized (LOCK) {
            Entry entry = entry(databasePath, generation);
            if (entry.pendingRefs == null) {
                entry.pendingRefs = loader.get();
            }
            return entry.pendingRefs;
        }
    }

    // 作用：取得（必要时创建）当前库与代的缓存项；逻辑：库或代变化时整体替换。
    private static Entry entry(String databasePath, int generation) {
        if (cached == null
                || !databasePath.equals(cached.path)
                || generation != cached.generation) {
            cached = new Entry(databasePath, generation);
        }
        return cached;
    }
}
