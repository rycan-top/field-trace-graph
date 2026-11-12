package io.fieldtracegraph.core.support;

import io.fieldtracegraph.core.shared.hash.Sha256Digest;

// 作用：生成稳定 ID 的通用工具。
// 使用场景：边、符号、项目与逻辑边键的标识生成。

/**
 * Backward-compatible alias for {@link Sha256Digest}. New code should import
 * the shared hash helper directly; this class only keeps existing call sites
 * stable during the migration.
 */
public final class Ids {
    // 作用：阻止实例化工具类；逻辑：构造器保持私有。
    private Ids() {
    }

    // 作用：生成稳定 SHA-256 ID；逻辑：把分段内容转交共享摘要工具统一计算。
    public static String sha256(String... parts) {
        return Sha256Digest.of(parts);
    }
}
