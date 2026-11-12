package io.fieldtracegraph.core.model;

import java.util.List;

// 作用：值节点记录，覆盖字段、参数、局部变量与返回值。
// 使用场景：构成血缘图的顶点，并支撑目标表达式解析。

public record ValueNodeRecord(
        String valueId,
        String scopeSymbolId,
        String fileId,
        String valueKind,
        String canonicalValue,
        String rootName,
        List<String> accessPath,
        int declarationOffset,
        int definitionOrdinal,
        String literalKind,
        int lineStart,
        int columnStart,
        int lineEnd,
        int columnEnd,
        String originFile,
        int originLineStart,
        int originColumnStart,
        int originLineEnd,
        int originColumnEnd
) {
}
