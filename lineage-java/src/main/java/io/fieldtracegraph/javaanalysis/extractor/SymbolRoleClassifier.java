package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.util.List;

// 作用：区分字段、局部变量与参数等符号角色。
// 使用场景：决定数据边的端点类型与追踪策略。

/**
 * Separates declared callables into the roles a caller cares about when
 * comparing two code graphs: business methods, generated-style accessors and
 * initializers. Accessors are kept in the graph (their field read/write is real
 * evidence) but must be countable apart from business logic.
 */
public final class SymbolRoleClassifier {
    public static final String BUSINESS = "BUSINESS";
    public static final String ACCESSOR_GET = "ACCESSOR_GET";
    public static final String ACCESSOR_SET = "ACCESSOR_SET";
    public static final String INITIALIZER = "INITIALIZER";

    // 作用：阻止工具类被实例化；逻辑：私有空构造器。
    private SymbolRoleClassifier() {
    }

    // 作用：判定可调用体角色；逻辑：初始化器优先，其次识别 getter、setter，其余归为业务方法。
    public static String of(CallableDeclaration<?> callable) {
        if (!(callable instanceof MethodDeclaration method)) {
            return BUSINESS;
        }
        String name = method.getNameAsString();
        if ("<clinit>".equals(name) || "<init>".equals(name)) {
            return INITIALIZER;
        }
        if (isGetter(method, name)) {
            return ACCESSOR_GET;
        }
        if (isSetter(method, name)) {
            return ACCESSOR_SET;
        }
        return BUSINESS;
    }

    /**
     * A getter is a no-argument, field-shaped name whose whole body returns one
     * field or one value directly. The body check keeps business methods that
     * merely start with {@code get} out of the accessor bucket.
     */
    // 作用：判断是否为 getter；逻辑：无参且带访问器前缀，方法体单句返回字段或值引用。
    private static boolean isGetter(MethodDeclaration method, String name) {
        if (!method.getParameters().isEmpty() || !hasAccessorPrefix(name, "get", "is", "has")) {
            return false;
        }
        return singleStatement(method)
                .filter(ReturnStmt.class::isInstance)
                .map(ReturnStmt.class::cast)
                .flatMap(ReturnStmt::getExpression)
                .filter(SymbolRoleClassifier::isFieldOrValueReference)
                .isPresent();
    }

    /**
     * A setter is a one-argument {@code setX} whose whole body assigns that
     * argument to a field.
     */
    // 作用：判断是否为 setter；逻辑：单参且带 set 前缀，方法体单句把该参数赋给字段。
    private static boolean isSetter(MethodDeclaration method, String name) {
        List<Parameter> parameters = method.getParameters();
        if (parameters.size() != 1 || !hasAccessorPrefix(name, "set")) {
            return false;
        }
        String parameter = parameters.get(0).getNameAsString();
        return singleStatement(method)
                .filter(ExpressionStmt.class::isInstance)
                .map(ExpressionStmt.class::cast)
                .map(ExpressionStmt::getExpression)
                .filter(AssignExpr.class::isInstance)
                .map(AssignExpr.class::cast)
                .filter(assign -> assign.getTarget() instanceof FieldAccessExpr)
                .map(AssignExpr::getValue)
                .filter(NameExpr.class::isInstance)
                .map(NameExpr.class::cast)
                .map(NameExpr::getNameAsString)
                .filter(parameter::equals)
                .isPresent();
    }

    // 作用：判断访问器前缀；逻辑：名称以指定前缀开头且后一字符为大写。
    private static boolean hasAccessorPrefix(String name, String... prefixes) {
        for (String prefix : prefixes) {
            if (name.length() > prefix.length()
                    && name.startsWith(prefix)
                    && Character.isUpperCase(name.charAt(prefix.length()))) {
                return true;
            }
        }
        return false;
    }

    // 作用：取方法体唯一语句；逻辑：方法体存在且仅一条语句时返回该语句。
    private static java.util.Optional<Statement> singleStatement(MethodDeclaration method) {
        return method.getBody()
                .filter(body -> body.getStatements().size() == 1)
                .map(body -> body.getStatement(0));
    }

    // 作用：判断是否为字段或值引用；逻辑：字段访问或简单变量名视为可直接返回的访问器表达式。
    private static boolean isFieldOrValueReference(Expression expression) {
        return expression instanceof FieldAccessExpr || expression instanceof NameExpr;
    }
}
