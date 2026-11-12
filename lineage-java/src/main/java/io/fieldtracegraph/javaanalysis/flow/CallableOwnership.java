package io.fieldtracegraph.javaanalysis.flow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;

// 作用：记录符号或调用点属于哪个可调用体。
// 使用场景：确定数据边应归属到哪个方法。

/**
 * Keeps extraction scoped to the callable that owns a node. Lambda bodies are
 * part of the enclosing method, while a method declared in an anonymous class
 * only contributes when it is an explicitly owned anonymous body.
 */
public final class CallableOwnership {
    // 作用：阻止工具类被实例化；逻辑：私有空构造器。
    private CallableOwnership() {
    }

    // 作用：判断节点是否归属指定可调用体；逻辑：取最近的祖先可调用体并与目标比较。
    public static boolean belongsToCallable(Node node, CallableDeclaration<?> owner) {
        return node.findAncestor(CallableDeclaration.class)
                .map(owner::equals)
                .orElse(false);
    }

    // 作用：判断节点是否属于目标或其中匿名类体；逻辑：最近可调用体匹配，或匿名类体的外层可调用体匹配。
    public static boolean belongsToCallableOrAnonymousBody(Node node, CallableDeclaration<?> owner) {
        CallableDeclaration<?> nearest = node.findAncestor(CallableDeclaration.class).orElse(null);
        if (nearest == null) {
            return false;
        }
        if (nearest.equals(owner)) {
            return true;
        }
        return nearest.findAncestor(ObjectCreationExpr.class)
                .filter(creation -> creation.getAnonymousClassBody().isPresent())
                .flatMap(creation -> creation.findAncestor(CallableDeclaration.class))
                .map(owner::equals)
                .orElse(false);
    }

    /**
     * A captured member write is a write to a field or array element of an
     * outer parameter/local from a lambda or anonymous body. Shadowed names do
     * not count.
     */
    // 作用：判断是否为捕获成员写；逻辑：定位非目标的 Lambda 或匿名体，排除同名遮蔽后确认根名来自外层。
    public static boolean isCapturedMemberWrite(AssignExpr assignment, CallableDeclaration<?> owner) {
        if (!(assignment.getTarget() instanceof FieldAccessExpr)
                && !(assignment.getTarget() instanceof ArrayAccessExpr)) {
            return false;
        }
        if (!belongsToCallableOrAnonymousBody(assignment, owner)) {
            return false;
        }
        Node scope = assignment.findAncestor(candidate ->
                candidate instanceof CallableDeclaration<?> || candidate instanceof LambdaExpr
        , Node.class).orElse(null);
        if (scope == null || scope.equals(owner)) {
            return false;
        }
        String root = rootName(assignment.getTarget());
        if (root == null || root.isBlank() || "this".equals(root)) {
            return false;
        }
        boolean shadowed = scope.findAll(Parameter.class).stream()
                .anyMatch(parameter -> root.equals(parameter.getNameAsString()))
                || scope.findAll(VariableDeclarator.class).stream()
                .anyMatch(variable -> root.equals(variable.getNameAsString()));
        if (shadowed) {
            return false;
        }
        return owner.getParameters().stream()
                .anyMatch(parameter -> root.equals(parameter.getNameAsString()))
                || owner.findAll(VariableDeclarator.class).stream()
                .anyMatch(variable -> root.equals(variable.getNameAsString()));
    }

    // 作用：提取表达式根名；逻辑：逐层剥离字段访问与数组下标，返回变量名或 this。
    public static String rootName(Expression expression) {
        Expression current = expression;
        while (current instanceof FieldAccessExpr field) {
            current = field.getScope();
        }
        while (current instanceof ArrayAccessExpr access) {
            current = access.getName();
        }
        if (current instanceof ThisExpr) {
            return "this";
        }
        return current instanceof NameExpr name ? name.getNameAsString() : null;
    }
}
