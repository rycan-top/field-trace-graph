package io.fieldtracegraph.core.model;

import java.util.List;

// 作用：类、方法、字段等符号记录。
// 使用场景：索引主表存储，并支撑 methods 搜索与调用解析。

public record SymbolRecord(
        String symbolId,
        String fileId,
        String moduleId,
        String kind,
        String role,
        String qname,
        String simpleName,
        String declaringType,
        List<String> parameterTypes,
        String returnType,
        String parentSymbolId,
        int lineStart,
        int columnStart,
        int lineEnd,
        int columnEnd
) {
}
