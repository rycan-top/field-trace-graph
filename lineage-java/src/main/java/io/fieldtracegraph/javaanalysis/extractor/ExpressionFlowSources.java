package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.YieldStmt;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.resolution.BuilderChainClassifier;
import io.fieldtracegraph.javaanalysis.resolution.NonLineageCallFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// 作用：从表达式中找出可作为值来源的子表达式。
// 使用场景：赋值右侧、返回语句与调用实参的追踪起点。

/**
 * Resolves the value-producing inputs of an expression without deciding how
 * those inputs are persisted. Assignments and setter writes share this rule.
 */
final class ExpressionFlowSources {
    private static final NonLineageCallFilter NON_LINEAGE_CALL_FILTER = new NonLineageCallFilter();

    // 作用：阻止工具类被实例化；逻辑：私有空构造器。
    private ExpressionFlowSources() {
    }

    // 作用：收集表达式来源；逻辑：递归遍历并按键与边类型去重后返回不可变列表。
    static List<Source> collect(Expression expression, JavaValueFlowContext context) {
        Set<String> keys = new LinkedHashSet<>();
        List<Source> result = new ArrayList<>();
        collect(expression, keys, result, context);
        return List.copyOf(result);
    }

    // 作用：递归解析来源；逻辑：按运算、三元、switch、下标、getter、容器读取、转换调用与普通调用分类处理。
    private static void collect(
            Expression expression,
            Set<String> keys,
            List<Source> result,
            JavaValueFlowContext context
    ) {
        Expression value = unwrap(expression);
        if (value instanceof BinaryExpr binary) {
            collect(binary.getLeft(), keys, result, context);
            collect(binary.getRight(), keys, result, context);
            return;
        }
        if (value instanceof UnaryExpr unary) {
            collect(unary.getExpression(), keys, result, context);
            return;
        }
        if (value instanceof ConditionalExpr conditional) {
            // A condition controls whether a branch is selected; it is not a
            // value input of the selected result. Control dependencies are
            // represented by CONDITION_USES edges instead.
            collect(conditional.getThenExpr(), keys, result, context);
            collect(conditional.getElseExpr(), keys, result, context);
            return;
        }
        if (value instanceof SwitchExpr switchExpression) {
            List<Expression> armValues = switchArmValues(switchExpression);
            for (Expression armValue : armValues) {
                collect(armValue, keys, result, context);
            }
            if (armValues.isEmpty()) {
                add(context.key(value), "ASSIGNED_FROM", keys, result);
            }
            return;
        }
        if (value instanceof ArrayAccessExpr) {
            add(context.key(value), "READS_ELEMENT", keys, result);
            return;
        }
        if (value instanceof MethodCallExpr call && context.isGetter(call)) {
            add(context.key(call), "FIELD_READ", keys, result);
            return;
        }
        if (value instanceof MethodCallExpr call) {
            if (context.containerReadKey(call) != null) {
                add(context.key(call), "READS_ELEMENT", keys, result);
                return;
            }
            if (context.isStreamCall(call)) {
                add(context.key(call), "ASSIGNED_FROM", keys, result);
                return;
            }
            String receiverField = NON_LINEAGE_CALL_FILTER.isValueTransform(call, context)
                    ? receiverFieldRead(call, context)
                    : null;
            if (receiverField != null) {
                // Conversion/normalization calls such as toString(), setScale()
                // or trim() do not change which field the value came from.
                add(receiverField, "FIELD_READ", keys, result);
                return;
            }
            if (NON_LINEAGE_CALL_FILTER.isValueTransform(call, context)) {
                call.getScope().ifPresent(scope -> {
                    if (!(scope instanceof ThisExpr)
                            && !(scope instanceof NameExpr name && isLikelyTypeName(name.getNameAsString()))) {
                        collect(scope, keys, result, context);
                    }
                });
                for (Expression argument : call.getArguments()) {
                    collect(argument, keys, result, context);
                }
                return;
            }
            if (BuilderChainClassifier.isBuildResult(call)) {
                add(context.key(call), "BUILDER_RESULT", keys, result);
                return;
            }
            // With no field root, the call result is the only known value.
            // Its arguments are inputs of the call, not direct inputs of the
            // assignment target; ARGUMENT_TO bridges carry that relationship.
            add(context.key(call), "ASSIGNED_FROM", keys, result);
            return;
        }
        if (value instanceof com.github.javaparser.ast.expr.ObjectCreationExpr creation) {
            add(context.key(creation), "ASSIGNED_FROM", keys, result);
            return;
        }
        String key = context.key(value);
        String edgeType = value instanceof LiteralExpr ? "DERIVED_FROM" : "ASSIGNED_FROM";
        add(key, edgeType, keys, result);
    }

    // 作用：登记一个来源；逻辑：空键跳过，按键与边类型去重后加入结果。
    private static void add(String key, String edgeType, Set<String> keys, List<Source> result) {
        if (key == null || key.isBlank() || keys.add(key + "\u0001" + edgeType)) {
            if (key != null && !key.isBlank()) {
                result.add(new Source(key, edgeType));
            }
        }
    }

    // 作用：去除表达式包装；逻辑：循环剥离括号与强制类型转换。
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

    /**
     * Returns the value-producing expressions of a switch expression. Arrow
     * arms normally contain one expression statement; block arms express their
     * result with {@code yield}. Throwing arms have no value input.
     */
    // 作用：收集 switch 表达式各分支值；逻辑：逐个分支提取表达式或 yield 的值。
    static List<Expression> switchArmValues(SwitchExpr switchExpression) {
        List<Expression> result = new ArrayList<>();
        for (var entry : switchExpression.getEntries()) {
            result.addAll(switchEntryValues(entry));
        }
        return List.copyOf(result);
    }

    // 作用：收集单个 switch 分支值；逻辑：表达式分支取表达式语句，块分支只取属于本 switch 的 yield。
    static List<Expression> switchEntryValues(SwitchEntry entry) {
        List<Expression> result = new ArrayList<>();
        SwitchExpr owner = entry.findAncestor(SwitchExpr.class).orElse(null);
        if (entry.getType() == SwitchEntry.Type.EXPRESSION) {
            for (Statement statement : entry.getStatements()) {
                if (statement instanceof ExpressionStmt expressionStatement) {
                    result.add(expressionStatement.getExpression());
                }
            }
            return List.copyOf(result);
        }
        // In block and statement-group arms, expression statements are
        // ordinary side effects. Only yield statements produce the switch
        // expression's value. Yields belonging to a nested switch expression
        // are values of that inner switch, not this one.
        for (Statement statement : entry.getStatements()) {
            for (YieldStmt yield : statement.findAll(YieldStmt.class)) {
                if (owner == null || yield.findAncestor(SwitchExpr.class)
                        .filter(owner::equals)
                        .isPresent()) {
                    result.add(yield.getExpression());
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns the deepest accessor field in a call receiver chain. For
     * {@code order.getPrice().setScale(2).toString()} this returns
     * {@code order.price}, so a converted scalar still keeps its field origin
     * without treating every call argument as a direct value source.
     */
    // 作用：查找接收者链上的字段读取；逻辑：逐层剥离转换调用与字段访问，返回最深的 getter 或容器读取键。
    private static String receiverFieldRead(MethodCallExpr call, JavaValueFlowContext context) {
        Expression current = call.getScope().orElse(null);
        while (current != null) {
            if (current instanceof MethodCallExpr receiverCall) {
                if (context.isGetter(receiverCall)) {
                    return context.key(receiverCall);
                }
                if (context.containerReadKey(receiverCall) != null
                        || context.isStreamCall(receiverCall)) {
                    return context.key(receiverCall);
                }
                current = receiverCall.getScope().orElse(null);
                continue;
            }
            if (current instanceof com.github.javaparser.ast.expr.FieldAccessExpr field) {
                current = field.getScope();
                continue;
            }
            return null;
        }
        return null;
    }

    // 作用：猜测是否为类型名；逻辑：首字母大写视为可能的静态类型限定。
    private static boolean isLikelyTypeName(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    record Source(String key, String edgeType) {
    }
}
