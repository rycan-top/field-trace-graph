package io.fieldtracegraph.javaanalysis;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import io.fieldtracegraph.core.support.Ids;

import java.util.ArrayList;
import java.util.List;

// 作用：为符号生成稳定标识。
// 使用场景：跨文件引用、符号去重与调用解析。

public final class JavaSymbolIdentity {
    // 作用：阻止工具类被实例化；逻辑：私有空构造器。
    private JavaSymbolIdentity() {
    }

    // 作用：生成类型符号 ID；逻辑：用模块、类型标记与限定名做哈希。
    public static String classSymbolId(String moduleId, String typeQname) {
        return Ids.sha256(moduleId, "CLASS", typeQname);
    }

    // 作用：生成可调用体符号 ID；逻辑：用模块、可调用标记与限定签名做哈希。
    public static String callableSymbolId(String moduleId, String qname) {
        return Ids.sha256(moduleId, "CALLABLE", qname);
    }

    // 作用：拼装可调用体限定名；逻辑：组合类型名、方法名与逗号分隔的参数类型。
    public static String callableQname(String typeQname, CallableDeclaration<?> callable, List<String> parameterTypes) {
        String name = displayName(callable);
        return typeQname + "#" + name + "(" + String.join(",", parameterTypes) + ")";
    }

    // 作用：提取参数类型列表；逻辑：逐个读取参数类型，变长参数补省略号。
    public static List<String> parameterTypes(CallableDeclaration<?> callable) {
        List<String> result = new ArrayList<>(callable.getParameters().size());
        for (Parameter parameter : callable.getParameters()) {
            String type = parameter.getType().asString();
            if (parameter.isVarArgs() && !type.endsWith("...")) {
                type += "...";
            }
            result.add(type);
        }
        return List.copyOf(result);
    }

    // 作用：取可调用体显示名；逻辑：方法取方法名，其余（构造器）统一用初始化标记。
    public static String displayName(CallableDeclaration<?> callable) {
        return callable instanceof MethodDeclaration method ? method.getNameAsString() : "<init>";
    }

    // 作用：判定可调用体种类；逻辑：构造器返回 CONSTRUCTOR，其余返回 METHOD。
    public static String callableKind(CallableDeclaration<?> callable) {
        return callable instanceof ConstructorDeclaration ? "CONSTRUCTOR" : "METHOD";
    }

    // 作用：取返回类型；逻辑：方法返回声明类型，构造器返回 null。
    public static String returnType(CallableDeclaration<?> callable) {
        return callable instanceof MethodDeclaration method ? method.getType().asString() : null;
    }

    // 作用：生成隐式构造器签名；逻辑：拼出无参初始化方法名。
    public static String implicitConstructorQname(String typeQname) {
        return typeQname + "#<init>()";
    }

    // 作用：生成记录规范构造器签名；逻辑：拼出带参数类型的初始化方法名。
    public static String recordCanonicalConstructorQname(String typeQname, List<String> parameterTypes) {
        return typeQname + "#<init>(" + String.join(",", parameterTypes) + ")";
    }

    /**
     * Record components are the canonical constructor parameters. Keep the
     * exact source spelling here so every producer of a record constructor
     * (call resolution, symbol publication and flow extraction) computes the
     * same stable name, including nested generic arguments.
     */
    // 作用：提取记录组件类型；逻辑：按声明顺序返回各组件类型源码写法。
    public static List<String> recordCanonicalParameterTypes(RecordDeclaration record) {
        return record.getParameters().stream()
                .map(parameter -> parameter.getType().asString())
                .toList();
    }

    // 作用：生成隐式构造器符号 ID；逻辑：基于隐式构造器签名计算可调用体哈希。
    public static String implicitConstructorSymbolId(String moduleId, String typeQname) {
        return callableSymbolId(moduleId, implicitConstructorQname(typeQname));
    }

    // 作用：判断类型是否需要隐式构造器；逻辑：接口、记录与注解不需要，普通类与枚举需要。
    public static boolean needsImplicitConstructor(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration classType) {
            return !classType.isInterface();
        }
        if (type instanceof RecordDeclaration) {
            return false;
        }
        return !(type instanceof AnnotationDeclaration);
    }

    /**
     * A compact constructor is declared without parameters but owns the
     * canonical parameter list of its record. It must not be published as a
     * zero-argument constructor.
     */
    // 作用：判断是否为记录紧凑构造器；逻辑：无参且未显式调用同类构造器的记录构造器视为紧凑形式。
    public static boolean isCompactRecordConstructor(
            TypeDeclaration<?> type,
            ConstructorDeclaration constructor
    ) {
        if (!(type instanceof RecordDeclaration record)
                || !constructor.getParameters().isEmpty()
                || record.getParameters().isEmpty()) {
            return false;
        }
        return constructor.getBody()
                .findAll(com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt.class)
                .stream()
                .noneMatch(invocation -> invocation.isThis());
    }

    /**
     * Returns whether an explicitly written constructor already has the
     * canonical record signature.
     */
    // 作用：判断是否为记录规范构造器；逻辑：参数数量与类型均和记录组件匹配时返回 true。
    public static boolean isCanonicalRecordConstructor(
            TypeDeclaration<?> type,
            ConstructorDeclaration constructor
    ) {
        if (!(type instanceof RecordDeclaration record)
                || constructor.getParameters().size() != record.getParameters().size()) {
            return false;
        }
        List<String> componentTypes = recordCanonicalParameterTypes(record);
        List<String> constructorTypes = parameterTypes(constructor);
        if (componentTypes.size() != constructorTypes.size()) {
            return false;
        }
        for (int index = 0; index < componentTypes.size(); index++) {
            if (!normalizedType(componentTypes.get(index))
                    .equals(normalizedType(constructorTypes.get(index)))) {
                return false;
            }
        }
        return true;
    }

    // 作用：规范化类型文本；逻辑：去掉空白字符便于比较，null 视为空串。
    private static String normalizedType(String type) {
        if (type == null) {
            return "";
        }
        return type.replace(" ", "").replace("\t", "");
    }
}
