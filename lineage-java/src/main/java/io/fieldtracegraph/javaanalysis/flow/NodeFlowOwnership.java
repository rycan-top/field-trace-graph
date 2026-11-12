package io.fieldtracegraph.javaanalysis.flow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;

// 作用：记录节点在作用域中的归属关系。
// 使用场景：跨类、嵌套类与 Lambda 的节点归属判定。

/**
 * Distinguishes statements executed by the outer callable from code that runs
 * later inside a lambda or anonymous-class method.
 */
public final class NodeFlowOwnership {
    // 作用：阻止工具类被实例化；逻辑：私有空构造器。
    private NodeFlowOwnership() {
    }

    // 作用：判断节点是否属于外层可执行体；逻辑：要求归属当前可调用体且向上无 Lambda 阻隔。
    public static boolean belongsToOuterExecutable(Node node, CallableDeclaration<?> owner) {
        if (!CallableOwnership.belongsToCallable(node, owner)) {
            return false;
        }
        for (Node current = node.getParentNode().orElse(null);
             current != null;
             current = current.getParentNode().orElse(null)) {
            if (current instanceof LambdaExpr) {
                return false;
            }
            if (current instanceof CallableDeclaration<?> callable) {
                return callable.equals(owner);
            }
        }
        return false;
    }
}
