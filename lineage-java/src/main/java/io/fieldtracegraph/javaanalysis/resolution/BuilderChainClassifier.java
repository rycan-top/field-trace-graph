package io.fieldtracegraph.javaanalysis.resolution;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 作用：识别 Builder 链式调用模式。
// 使用场景：避免把链式配置方法误判为普通业务调用。

/**
 * Recognizes fluent {@code builder()...build()} chains without requiring the
 * generated builder type to be present in the analysed source set.
 */
public final class BuilderChainClassifier {
    // 作用：阻止实例化工具类；逻辑：构造器保持私有。
    private BuilderChainClassifier() {
    }

    // 作用：判断调用是否属于 Builder 链；逻辑：是 build 结果，或起点为 builder 且向上可达 build 时成立。
    public static boolean isBuilderCall(MethodCallExpr call) {
        return isBuildResult(call) || (hasBuilderRoot(call) && reachesBuild(call));
    }

    // 作用：判断调用是否为 build 终态；逻辑：直接复用 build 判定。
    public static boolean isBuildResult(MethodCallExpr call) {
        return isBuild(call);
    }

    // 作用：收集 build 链上的单参数配置调用；逻辑：从 build 接收者向根回溯，遇到 Builder 构造或 builder 起点后反转返回。
    public static List<MethodCallExpr> properties(MethodCallExpr build) {
        if (!isBuild(build)) {
            return List.of();
        }
        List<MethodCallExpr> stages = new ArrayList<>();
        Expression receiver = build.getScope().orElse(null);
        while (receiver != null) {
            Expression value = unwrap(receiver);
            if (value instanceof ObjectCreationExpr creation
                    && creation.getType().getNameAsString().endsWith("Builder")) {
                Collections.reverse(stages);
                return stages.stream()
                        .filter(stage -> stage.getArguments().size() == 1)
                        .toList();
            }
            if (!(value instanceof MethodCallExpr call)) {
                return List.of();
            }
            if (isBuilderRoot(call)) {
                Collections.reverse(stages);
                return stages.stream()
                        .filter(stage -> stage.getArguments().size() == 1)
                        .toList();
            }
            stages.add(call);
            receiver = call.getScope().orElse(null);
        }
        return List.of();
    }

    // 作用：判断是否为无参 build 调用；逻辑：方法名为 build、无实参且整条链存在 Builder 根。
    private static boolean isBuild(MethodCallExpr call) {
        return "build".equals(call.getNameAsString())
                && call.getArguments().isEmpty()
                && hasBuilderRoot(call);
    }

    // 作用：判断调用链是否存在 Builder 根；逻辑：沿接收者回溯，命中 builder() 或名称以 Builder 结尾的构造。
    private static boolean hasBuilderRoot(MethodCallExpr call) {
        if (isBuilderRoot(call)) {
            return true;
        }
        Expression receiver = call.getScope().orElse(null);
        while (receiver != null) {
            Expression value = unwrap(receiver);
            if (value instanceof MethodCallExpr previous) {
                if (isBuilderRoot(previous)) {
                    return true;
                }
                receiver = previous.getScope().orElse(null);
            } else {
                return value instanceof ObjectCreationExpr creation
                        && creation.getType().getNameAsString().endsWith("Builder");
            }
        }
        return false;
    }

    // 作用：判断当前调用是否继续通向 build；逻辑：沿父表达式逐层检查外层链，直到遇到 build 或链中断。
    private static boolean reachesBuild(MethodCallExpr call) {
        Node current = call;
        while (true) {
            Node parent = current.getParentNode().orElse(null);
            if (!(parent instanceof MethodCallExpr outer)
                    || outer.getScope().map(BuilderChainClassifier::unwrap).orElse(null) != current) {
                return false;
            }
            if (isBuild(outer)) {
                return true;
            }
            current = outer;
        }
    }

    // 作用：判断是否为 Builder 起点；逻辑：要求无参 builder 方法且存在接收者。
    private static boolean isBuilderRoot(MethodCallExpr call) {
        return "builder".equals(call.getNameAsString())
                && call.getArguments().isEmpty()
                && call.getScope().isPresent();
    }

    // 作用：去掉表达式外层括号；逻辑：循环剥离 EnclosedExpr 直到得到真实表达式。
    private static Expression unwrap(Expression expression) {
        Expression value = expression;
        while (value instanceof EnclosedExpr enclosed) {
            value = enclosed.getInner();
        }
        return value;
    }
}
