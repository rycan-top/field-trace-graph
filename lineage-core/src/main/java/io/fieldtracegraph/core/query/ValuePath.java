package io.fieldtracegraph.core.query;

import java.util.List;

// 作用：表示一条值路径。
// 使用场景：记录从起点到当前节点的完整链路。

/**
 * Renders field and container suffixes without inserting dots before bracket
 * segments, for example {@code ["tabs"][*].defaultOn}.
 */
final class ValuePath {
    // 作用：工具类私有构造；逻辑：禁止实例化。
    private ValuePath() {
    }

    // 作用：把字段路径拼成后缀字符串；逻辑：用点号连接各段，空路径返回空串。
    static String suffix(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            if (result.length() > 0
                    && !segment.startsWith("[")
                    && !segment.startsWith(".")) {
                result.append('.');
            }
            result.append(segment);
        }
        return result.toString();
    }
}
