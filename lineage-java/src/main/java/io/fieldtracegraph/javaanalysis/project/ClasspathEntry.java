package io.fieldtracegraph.javaanalysis.project;

// 作用：记录单条 classpath 条目。
// 使用场景：classpath 清单读取、去重与上下文指纹计算。

/**
 * One approved project dependency. Canonical manifests also declare a SHA-256
 * so the analysed bytes are deterministic across machines.
 */
public record ClasspathEntry(String path, String scope, String moduleId, String sha256) {
    // 作用：创建不带哈希的 classpath 条目；逻辑：把摘要置空并委托给主构造器。
    public ClasspathEntry(String path, String scope, String moduleId) {
        this(path, scope, moduleId, null);
    }
}
