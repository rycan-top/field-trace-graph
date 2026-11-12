package io.fieldtracegraph.javaanalysis.resolution;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.Set;

// 作用：提供值类型判定与转换工具。
// 使用场景：基本类型、包装类型与字符串之间的类型兼容判断。

/**
 * Recognizes JDK value-shaped types. This is deliberately type based, not
 * business-name based: value-object methods keep value flow, while methods on
 * application services remain call boundaries.
 */
public final class JavaValueTypes {
    private static final Set<String> VALUE_TYPES = Set.of(
            "String", "CharSequence", "StringBuilder", "StringBuffer",
            "Boolean", "Byte", "Short", "Integer", "Long", "Float", "Double", "Character",
            "Number", "BigInteger", "BigDecimal", "AtomicInteger", "AtomicLong",
            "Optional", "OptionalInt", "OptionalLong", "OptionalDouble",
            "Date", "Calendar", "GregorianCalendar", "Instant", "LocalDate", "LocalTime",
            "LocalDateTime", "ZonedDateTime", "OffsetDateTime", "OffsetTime", "Duration",
            "Period", "Year", "YearMonth", "MonthDay", "ZoneId", "ZoneOffset",
            "UUID", "URI", "URL", "Path", "File", "Locale", "Pattern", "Matcher",
            "Collection", "List", "Set", "Map", "Queue", "Deque", "Iterator", "Stream",
            "IntStream", "LongStream", "DoubleStream"
    );

    // 作用：阻止实例化工具类；逻辑：构造器保持私有。
    private JavaValueTypes() {
    }

    // 作用：判断类型是否为 JDK 值类型；逻辑：取简单类型名后查内置值类型集合。
    public static boolean isValueType(String typeName) {
        return VALUE_TYPES.contains(simpleName(typeName));
    }

    // 作用：判断接收者是否承载值类型；逻辑：解包后按构造、强转、变量与 this 字段分别取声明类型判定。
    public static boolean isValueReceiver(Expression receiver, CallableDeclaration<?> callable) {
        Expression value = unwrap(receiver);
        if (value == null) {
            return false;
        }
        if (value instanceof ObjectCreationExpr creation) {
            return isValueType(creation.getType().asString());
        }
        if (value instanceof CastExpr cast) {
            return isValueType(cast.getType().asString());
        }
        if (value instanceof NameExpr name) {
            return isValueType(declaredType(name.getNameAsString(), callable));
        }
        if (value instanceof FieldAccessExpr field && field.getScope().isThisExpr()) {
            return isValueType(declaredType(field.getNameAsString(), callable));
        }
        return false;
    }

    // 作用：查找名称的声明类型；逻辑：依次检查参数、局部变量与所属类型字段，未命中的返回空。
    private static String declaredType(String name, CallableDeclaration<?> callable) {
        if (name == null || name.isBlank() || callable == null) {
            return null;
        }
        for (Parameter parameter : callable.getParameters()) {
            if (name.equals(parameter.getNameAsString())) {
                return parameter.getType().asString();
            }
        }
        for (VariableDeclarator variable : callable.findAll(VariableDeclarator.class)) {
            if (name.equals(variable.getNameAsString())) {
                return variable.getType().asString();
            }
        }
        TypeDeclaration<?> owner = callable.findAncestor(TypeDeclaration.class).orElse(null);
        if (owner == null) {
            return null;
        }
        for (FieldDeclaration field : owner.getFields()) {
            for (VariableDeclarator variable : field.getVariables()) {
                if (name.equals(variable.getNameAsString())) {
                    return field.getElementType().asString();
                }
            }
        }
        return null;
    }

    // 作用：提取类型简单名；逻辑：去掉泛型参数与包名前缀后返回末段名称。
    private static String simpleName(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return "";
        }
        String type = typeName.trim();
        int generic = type.indexOf('<');
        if (generic >= 0) {
            type = type.substring(0, generic);
        }
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }

    // 作用：剥掉表达式括号与强转；逻辑：循环解包直到得到原始表达式。
    private static Expression unwrap(Expression expression) {
        Expression value = expression;
        while (true) {
            if (value instanceof EnclosedExpr enclosed) {
                value = enclosed.getInner();
            } else if (value instanceof CastExpr cast) {
                value = cast.getExpression();
            } else {
                return value;
            }
        }
    }
}
