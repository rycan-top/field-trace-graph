package io.fieldtracegraph.javaanalysis.resolution;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.PrimitiveType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 作用：解析访问器方法对应的字段。
// 使用场景：把 getX() 与 setX(v) 识别为字段 x 的读写。

/**
 * Recognizes explicit and annotation-generated getter/setter accessors and maps
 * them to the field they read or write. This is used to enrich method
 * summaries; explicit accessor bodies are still analyzed by the normal
 * extractors.
 */
public final class AccessorResolver {
    public record Accessor(String methodName, String fieldName, String kind) {
    }

    // 作用：解析类型中的读写访问器；逻辑：先按 Lombok 注解生成字段访问器，再补充显式 get/is/set 方法。
    public List<Accessor> resolve(TypeDeclaration<?> type) {
        List<Accessor> result = new ArrayList<>();
        boolean typeGetter = hasAnnotation(type, "Getter");
        boolean typeSetter = hasAnnotation(type, "Setter");
        boolean typeData = hasAnnotation(type, "Data");
        boolean typeValue = hasAnnotation(type, "Value");
        boolean fluent = hasAnnotationText(type, "Accessors", "fluent");

        for (FieldDeclaration field : type.getFields()) {
            boolean fieldGetter = typeGetter || typeData || typeValue || hasAnnotation(field, "Getter");
            boolean fieldSetter = !typeValue
                    && (typeSetter || typeData || hasAnnotation(field, "Setter"));
            for (VariableDeclarator variable : field.getVariables()) {
                String fieldName = variable.getNameAsString();
                if (fieldGetter) {
                    result.add(new Accessor(
                            fluent ? fieldName : "get" + capitalize(fieldName),
                            fieldName,
                            "READ"
                    ));
                }
                if (fieldSetter) {
                    result.add(new Accessor(
                            fluent ? fieldName : "set" + capitalize(fieldName),
                            fieldName,
                            "WRITE"
                    ));
                }
            }
        }

        Set<String> declaredFields = declaredFieldNames(type);
        for (MethodDeclaration method : type.getMethods()) {
            String name = method.getNameAsString();
            if (name.length() > 3 && name.startsWith("get") && method.getParameters().isEmpty() && !isVoid(method)) {
                addIfDeclared(result, declaredFields, name, decapitalize(name.substring(3)), "READ");
            } else if (name.length() > 2 && name.startsWith("is") && method.getParameters().isEmpty() && isBoolean(method)) {
                addIfDeclared(result, declaredFields, name, decapitalize(name.substring(2)), "READ");
            } else if (name.length() > 3 && name.startsWith("set") && method.getParameters().size() == 1) {
                addIfDeclared(result, declaredFields, name, decapitalize(name.substring(3)), "WRITE");
            }
        }
        return List.copyOf(result);
    }

    // 作用：登记显式访问器；逻辑：仅当类型确实声明了该字段才登记，避免 setColorParam 这类
    // 名称像 setter、实际没有对应字段的方法生成幽灵 this.colorParam 节点。
    private void addIfDeclared(
            List<Accessor> result,
            Set<String> declaredFields,
            String methodName,
            String fieldName,
            String kind
    ) {
        if (declaresField(declaredFields, fieldName)) {
            result.add(new Accessor(methodName, fieldName, kind));
        }
    }

    // 作用：判断字段是否已声明；逻辑：兼容布尔字段 isX 与属性名 x 的两种写法。
    private boolean declaresField(Set<String> declaredFields, String fieldName) {
        return declaredFields.contains(fieldName)
                || declaredFields.contains("is" + capitalize(fieldName));
    }

    // 作用：收集类型直接声明的字段名；逻辑：遍历字段声明展开全部变量声明符。
    private Set<String> declaredFieldNames(TypeDeclaration<?> type) {
        Set<String> names = new HashSet<>();
        for (FieldDeclaration field : type.getFields()) {
            for (VariableDeclarator variable : field.getVariables()) {
                names.add(variable.getNameAsString());
            }
        }
        return names;
    }

    // 作用：判断节点是否带指定注解；逻辑：比较注解简单名或点号后缀，兼容全限定写法。
    private boolean hasAnnotation(Object node, String simpleName) {
        if (node instanceof com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> withAnnotations) {
            return withAnnotations.getAnnotations().stream()
                    .map(AnnotationExpr::getNameAsString)
                    .anyMatch(name -> name.equals(simpleName) || name.endsWith("." + simpleName));
        }
        return false;
    }

    // 作用：判断注解文本是否包含指定开关；逻辑：先按注解名筛选，再去空白后匹配“参数=true”。
    private boolean hasAnnotationText(Object node, String simpleName, String flag) {
        if (!(node instanceof com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> withAnnotations)) {
            return false;
        }
        return withAnnotations.getAnnotations().stream()
                .filter(annotation -> {
                    String name = annotation.getNameAsString();
                    return name.equals(simpleName) || name.endsWith("." + simpleName);
                })
                .map(AnnotationExpr::toString)
                .map(value -> value.replaceAll("\\s+", ""))
                .anyMatch(value -> value.contains(flag + "=true"));
    }

    // 作用：判断方法返回类型是否为 void；逻辑：调用 JavaParser 的 void 类型判定。
    private boolean isVoid(MethodDeclaration method) {
        return method.getType().isVoidType();
    }

    // 作用：判断方法是否返回原始布尔值；逻辑：要求是原始类型且类型为 boolean。
    private boolean isBoolean(MethodDeclaration method) {
        return method.getType().isPrimitiveType()
                && method.getType().asPrimitiveType().getType() == PrimitiveType.Primitive.BOOLEAN;
    }

    // 作用：把首字母转为大写；逻辑：空串原样返回，否则大写首字符后拼接其余部分。
    private String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    // 作用：把属性名首字母转为小写；逻辑：空串或前两个字母均大写时原样返回，否则小写首字符。
    private String decapitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        if (value.length() >= 2 && Character.isUpperCase(value.charAt(0)) && Character.isUpperCase(value.charAt(1))) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }
}
