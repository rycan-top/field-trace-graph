package io.fieldtracegraph.javaanalysis;

import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

// 作用：判定类型种类。
// 使用场景：区分类、接口、枚举、记录与注解，决定可用的字段与方法。

/**
 * Names the declared type flavour instead of collapsing every type into
 * {@code CLASS}. Comparisons against a code_node model that keeps interfaces
 * apart need the same vocabulary on both sides.
 */
public final class JavaTypeKind {
    public static final String CLASS = "CLASS";
    public static final String INTERFACE = "INTERFACE";
    public static final String ENUM = "ENUM";
    public static final String ANNOTATION = "ANNOTATION";
    public static final String RECORD = "RECORD";

    // 作用：阻止常量类被实例化；逻辑：私有空构造器。
    private JavaTypeKind() {
    }

    // 作用：判定类型种类；逻辑：依次识别接口、枚举、注解、记录，其余归为类。
    public static String of(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            return declaration.isInterface() ? INTERFACE : CLASS;
        }
        if (type instanceof EnumDeclaration) {
            return ENUM;
        }
        if (type instanceof AnnotationDeclaration) {
            return ANNOTATION;
        }
        if (type instanceof RecordDeclaration) {
            return RECORD;
        }
        return CLASS;
    }
}
