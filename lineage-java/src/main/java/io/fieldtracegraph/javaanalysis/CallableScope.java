package io.fieldtracegraph.javaanalysis;

import com.github.javaparser.ast.body.CallableDeclaration;

import java.util.List;

// 作用：方法、构造器与 Lambda 的作用域记录。
// 使用场景：判断每条数据边归属于哪个可调用体。

public record CallableScope(
        String classSymbolId,
        String typeQname,
        String symbolId,
        String qname,
        String displayName,
        List<String> parameterTypes,
        String returnType,
        CallableDeclaration<?> callable
) {
}
