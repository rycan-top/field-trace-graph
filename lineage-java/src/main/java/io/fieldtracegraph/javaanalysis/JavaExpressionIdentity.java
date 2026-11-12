package io.fieldtracegraph.javaanalysis;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.resolution.AccessorFieldIdentity;

import java.util.Set;
import java.util.regex.Pattern;

// 作用：为表达式生成稳定标识。
// 使用场景：跨语句识别同一表达式并去重。

public final class JavaExpressionIdentity {
    private static final int MAX_EXPRESSION_CHARS = 1000;
    private static final Pattern SAFE_MAP_KEY =
            Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_.$-]{0,63}");
    private static final Set<String> MAP_KEY_METHODS = Set.of(
            "put", "get", "getOrDefault", "containsKey", "remove"
    );

    // 作用：阻止工具类被实例化；逻辑：私有空构造器。
    private JavaExpressionIdentity() {
    }

    // 作用：生成表达式标识；逻辑：按变量、字段访问、调用、方法引用、创建与字面量分类编码。
    public static String key(Expression expression) {
        if (expression instanceof NameExpr name) {
            return name.getNameAsString();
        }
        if (expression instanceof FieldAccessExpr field) {
            return key(field.getScope()) + "." + AccessorFieldIdentity.canonicalPropertyName(field.getNameAsString());
        }
        if (expression instanceof MethodCallExpr call) {
            // A method name alone is not unique inside one method body. Keep the
            // readable method name in the display value, but make the identity
            // specific to the call expression so two calls cannot share nodes.
            return "call:" + call.getNameAsString()
                    + "(" + call.getArguments().size() + ")#"
                    + useSiteIdentity(call);
        }
        if (expression instanceof MethodReferenceExpr reference) {
            return "method-ref:" + sanitizedText(reference);
        }
        if (expression instanceof ObjectCreationExpr creation) {
            return "new:" + creation.getType().asString()
                    + "#" + useSiteIdentity(creation);
        }
        if (expression instanceof LiteralExpr literal) {
            return "literal:" + literalKind(literal) + ":" + Ids.sha256(literal.toString());
        }
        return sanitizedText(expression);
    }

    /**
     * Identity for a method call that has already been classified as a call.
     * Value-flow code uses this after its type-aware accessor check so a method
     * that merely looks like a getter cannot become a field again.
     */
    // 作用：生成调用表达式标识；逻辑：组合方法名、参数个数与使用点哈希。
    public static String callKey(MethodCallExpr call) {
        return "call:" + call.getNameAsString()
                + "(" + call.getArguments().size() + ")#"
                + useSiteIdentity(call);
    }

    /**
     * Identical source expressions may occur twice in one method. The AST text
     * alone therefore cannot identify a use site. Include the source range in
     * the internal key while keeping the user-facing display free of hashes.
     */
    // 作用：生成使用点标识；逻辑：结合表达式文本与起止位置做哈希并截取短码。
    private static String useSiteIdentity(Expression expression) {
        String location = expression.getBegin()
                .map(position -> position.line + ":" + position.column)
                .orElse("");
        String end = expression.getEnd()
                .map(position -> position.line + ":" + position.column)
                .orElse("");
        return Ids.sha256(sanitizedText(expression), location, end).substring(0, 12);
    }

    /**
     * Produces a deterministic expression identity without leaking raw string
     * or character literal content. Literal values are replaced by a hash so
     * distinct constants stay distinct while the stored text stays redacted.
     */
    // 作用：生成脱敏表达式文本；逻辑：克隆语法树并把字符串、文本块与字符字面量替换为哈希或占位。
    private static String sanitizedText(Expression expression) {
        Expression clone = expression.clone();
        clone.findAll(StringLiteralExpr.class)
                .forEach(literal -> literal.setValue("s" + Ids.sha256(literal.getValue())));
        clone.findAll(TextBlockLiteralExpr.class)
                .forEach(literal -> literal.setValue("t" + Ids.sha256(literal.getValue())));
        clone.findAll(CharLiteralExpr.class)
                .forEach(literal -> literal.setValue("?"));
        return clone.toString();
    }

    /**
     * Returns a display value that never contains raw string or character
     * literal content. Literal identity is still derived from {@link #key},
     * so distinct constants are not merged.
     */
    // 作用：生成展示文本；逻辑：字面量只显示种类，调用与创建只显示名称，其余复用标识。
    public static String display(Expression expression) {
        if (expression instanceof LiteralExpr literal) {
            return "literal:" + literalKind(literal);
        }
        if (expression instanceof MethodReferenceExpr reference) {
            return "method-ref:" + reference;
        }
        if (expression instanceof ObjectCreationExpr creation) {
            return "new:" + creation.getType().asString();
        }
        if (expression instanceof MethodCallExpr call) {
            return "call:" + call.getNameAsString() + "(" + call.getArguments().size() + ")";
        }
        return key(expression);
    }

    // 作用：生成规范化表达式；逻辑：脱敏后压缩空白并限制最大长度。
    public static String normalizedExpression(Expression expression) {
        String normalized = sanitizedText(expression).replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_EXPRESSION_CHARS
                ? normalized
                : normalized.substring(0, MAX_EXPRESSION_CHARS);
    }

    /**
     * Map keys are structural source names rather than business field values.
     * Keep safe identifier-like keys readable while every other string literal
     * in the operation remains redacted.
     */
    // 作用：规范化 Map 操作表达式；逻辑：仅保留安全 Map 键的明文，其余字面量脱敏后压缩空白并截断。
    public static String normalizedMapOperation(Expression expression) {
        Expression clone = expression.clone();
        clone.findAll(StringLiteralExpr.class).forEach(literal -> {
            if (!isSafeMapKeyLiteral(literal)) {
                literal.setValue("s" + Ids.sha256(literal.getValue()));
            }
        });
        clone.findAll(TextBlockLiteralExpr.class)
                .forEach(literal -> literal.setValue("t" + Ids.sha256(literal.getValue())));
        clone.findAll(CharLiteralExpr.class).forEach(literal -> literal.setValue("?"));
        String normalized = clone.toString().replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_EXPRESSION_CHARS
                ? normalized
                : normalized.substring(0, MAX_EXPRESSION_CHARS);
    }

    // 作用：判断是否为安全 Map 键；逻辑：值需符合标识符模式且位于下标或常见 Map 方法首参位置。
    private static boolean isSafeMapKeyLiteral(StringLiteralExpr literal) {
        if (!SAFE_MAP_KEY.matcher(literal.asString()).matches()) {
            return false;
        }
        com.github.javaparser.ast.Node parent = literal.getParentNode().orElse(null);
        if (parent instanceof com.github.javaparser.ast.expr.ArrayAccessExpr access) {
            return access.getIndex() == literal;
        }
        if (parent instanceof MethodCallExpr call
                && MAP_KEY_METHODS.contains(call.getNameAsString())
                && !call.getArguments().isEmpty()
                && call.getArgument(0) == literal) {
            return true;
        }
        return false;
    }

    // 作用：取字面量种类；逻辑：非字面量返回 null，字面量交给内部判定。
    public static String literalKindOf(Expression expression) {
        return expression instanceof LiteralExpr literal ? literalKind(literal) : null;
    }

    // 作用：判定字面量种类；逻辑：按字符串、字符、布尔、空与数字分类，其余归为普通字面量。
    private static String literalKind(LiteralExpr literal) {
        if (literal instanceof StringLiteralExpr) {
            return "STRING";
        }
        if (literal instanceof CharLiteralExpr) {
            return "CHAR";
        }
        if (literal instanceof BooleanLiteralExpr) {
            return "BOOLEAN";
        }
        if (literal instanceof NullLiteralExpr) {
            return "NULL";
        }
        if (literal instanceof IntegerLiteralExpr || literal instanceof LongLiteralExpr || literal instanceof DoubleLiteralExpr) {
            return "NUMBER";
        }
        return "LITERAL";
    }

    // 作用：判定节点种类；逻辑：按返回标记、字面量、调用结果、参数、局部变量与字段路径分类。
    public static String nodeKind(String key, CallableDeclaration<?> callable) {
        if ("RETURN".equals(key)) {
            return "RETURN";
        }
        if (key.startsWith("literal:")) {
            return "CONSTANT";
        }
        if (key.startsWith("call:") || key.startsWith("new:")) {
            return "CALL_RESULT";
        }
        if (key.startsWith("method-ref:")) {
            return "EXPRESSION";
        }
        if (key.startsWith("reference:")) {
            return "EXPRESSION";
        }
        boolean isParameter = callable.getParameters().stream()
                .anyMatch(candidate -> candidate.getNameAsString().equals(key));
        if (isParameter) {
            return "PARAMETER";
        }
        boolean isLocal = callable.findAll(VariableDeclarator.class).stream()
                .anyMatch(variable -> variable.getNameAsString().equals(key));
        if (isLocal) {
            return "LOCAL";
        }
        if (key.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*|\\[[^\\]]+\\])+")) {
            return "FIELD";
        }
        return "EXPRESSION";
    }
}
