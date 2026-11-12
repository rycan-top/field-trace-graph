package io.fieldtracegraph.core.shared.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// 作用：SHA-256 摘要工具。
// 使用场景：计算文件哈希、项目指纹与语义上下文指纹。

/**
 * Stateless SHA-256 helper used for stable identity hashes across the tool.
 * It deliberately has no business state and never opens storage or I/O.
 */
public final class Sha256Digest {
    // 作用：阻止实例化工具类；逻辑：构造器保持私有。
    private Sha256Digest() {
    }

    // 作用：计算字节数组的 SHA-256；逻辑：创建摘要器并输出小写十六进制结果。
    public static String of(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    // 作用：计算多段字符串的稳定摘要；逻辑：按长度前缀编码各段后统一转为字节并计算 SHA-256。
    public static String of(String... parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            String value = part == null ? "" : part;
            canonical.append(value.length()).append(':').append(value).append('\u001f');
        }
        return of(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }
}
