package io.fieldtracegraph.cli.project;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

// 作用：读取源文件内容并计算哈希。
// 使用场景：增量索引判定与分析前后内容校验。

/**
 * Decodes Java source bytes with BOM detection and a fallback for legacy
 * GB18030/GBK files. Strict UTF-8 is preferred; silently replacing invalid
 * sequences would corrupt the parser input, so decoding failures fall back to
 * the legacy charsets instead of producing replacement characters.
 */
public final class SourceFileReader {
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final Charset GBK = Charset.forName("GBK");

    // 作用：阻止工具类被实例化；逻辑：私有空构造器。
    private SourceFileReader() {
    }

    // 作用：解码源文件字节；逻辑：识别 BOM 选择 UTF-8、UTF-16，无 BOM 时优先 UTF-8 再回退国标编码。
    public static DecodedSource decode(byte[] bytes) throws CharacterCodingException {
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return decodeUtf8OrLegacy(slice(bytes, 3));
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new DecodedSource(decodeStrict(slice(bytes, 2), StandardCharsets.UTF_16BE), "UTF-16BE");
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new DecodedSource(decodeStrict(slice(bytes, 2), StandardCharsets.UTF_16LE), "UTF-16LE");
        }
        return decodeUtf8OrLegacy(bytes);
    }

    // 作用：按 UTF-8 或国标编码解码；逻辑：严格解码 UTF-8，失败后依次尝试 GB18030 与 GBK。
    private static DecodedSource decodeUtf8OrLegacy(byte[] bytes) throws CharacterCodingException {
        try {
            return new DecodedSource(decodeStrict(bytes, StandardCharsets.UTF_8), "UTF-8");
        } catch (CharacterCodingException utf8Failure) {
            try {
                return new DecodedSource(decodeStrict(bytes, GB18030), "GB18030");
            } catch (CharacterCodingException gb18030Failure) {
                try {
                    return new DecodedSource(decodeStrict(bytes, GBK), "GBK");
                } catch (CharacterCodingException gbkFailure) {
                    throw gbkFailure;
                }
            }
        }
    }

    // 作用：严格解码字节；逻辑：配置解码器对非法与不可映射字符报错后转换为字符串。
    private static String decodeStrict(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    // 作用：判断字节前缀；逻辑：逐字节比较给定序列，长度不足时返回 false。
    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((bytes[index] & 0xFF) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    // 作用：去掉字节前缀；逻辑：从指定下标复制剩余字节。
    private static byte[] slice(byte[] bytes, int from) {
        return Arrays.copyOfRange(bytes, from, bytes.length);
    }

    public record DecodedSource(String content, String encoding) {
    }
}
