package io.fieldtracegraph.javaanalysis.flow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 作用：解析条件语句对值流的影响。
// 使用场景：if、三元表达式与短路运算中的值选择。

/**
 * Resolves the control-flow predicates that guard a node. It keeps guard
 * collection separate from graph emission so call, assignment and mutation
 * extractors all describe branches with the same condition text.
 */
public final class GuardConditionResolver {
    private final CallableDeclaration<?> boundary;
    private final JavaValueFlowContext context;

    // 作用：绑定作用域与值流上下文；逻辑：保存可调用体边界与表达式规范化工具。
    public GuardConditionResolver(CallableDeclaration<?> boundary, JavaValueFlowContext context) {
        this.boundary = boundary;
        this.context = context;
    }

    // 作用：汇总节点守卫条件；逻辑：合并前置守卫与祖先分支条件后反转拼接。
    public String conditionFor(Node node) {
        List<String> conditions = new ArrayList<>(precedingGuards(node));
        conditions.addAll(ancestorBranchConditions(node));
        Collections.reverse(conditions);
        return String.join(" && ", conditions);
    }

    // 作用：把条件并入操作文本；逻辑：任一侧为空时返回另一侧，否则以逻辑与连接。
    public String withCondition(String operation, String condition) {
        if (condition == null || condition.isBlank()) {
            return operation == null ? "" : operation;
        }
        return operation == null || operation.isBlank() ? condition : operation + " && " + condition;
    }

    // 作用：判断节点是否受控；逻辑：向上查找条件、分支、循环或异常结构。
    public boolean isControlled(Node node) {
        for (Node current = node.getParentNode().orElse(null);
             current != null && current != boundary;
             current = current.getParentNode().orElse(null)) {
            if (current instanceof IfStmt
                    || current instanceof SwitchStmt
                    || current instanceof SwitchEntry
                    || current instanceof WhileStmt
                    || current instanceof DoStmt
                    || current instanceof ForStmt
                    || current instanceof ForEachStmt
                    || current instanceof TryStmt) {
                return true;
            }
        }
        return false;
    }

    // 作用：收集祖先分支条件；逻辑：按 if、switch、循环与 try 结构生成进入该节点的条件文本。
    public List<String> ancestorBranchConditions(Node node) {
        List<String> conditions = new ArrayList<>();
        for (Node current = node.getParentNode().orElse(null);
             current != null && current != boundary;
             current = current.getParentNode().orElse(null)) {
            if (current instanceof IfStmt branch) {
                if (isWithin(node, branch.getThenStmt())) {
                    conditions.add(conditionText(branch.getCondition()));
                } else if (branch.getElseStmt().filter(branchNode -> isWithin(node, branchNode)).isPresent()) {
                    conditions.add(negate(conditionText(branch.getCondition())));
                }
            } else if (current instanceof SwitchEntry entry) {
                entry.findAncestor(SwitchStmt.class).ifPresent(statement -> {
                    String selector = conditionText(statement.getSelector());
                    if (entry.getLabels().isEmpty()) {
                        conditions.add("default(" + selector + ")");
                    } else {
                        String cases = entry.getLabels().stream()
                                .map(label -> selector + " == " + conditionText(label))
                                .reduce((left, right) -> left + " || " + right)
                                .orElse(selector);
                        conditions.add(entry.getLabels().size() > 1 ? "(" + cases + ")" : cases);
                    }
                });
            } else if (current instanceof WhileStmt loop
                    && isWithin(node, loop.getBody())) {
                conditions.add(conditionText(loop.getCondition()));
            } else if (current instanceof DoStmt loop
                    && isWithin(node, loop.getBody())) {
                conditions.add("do");
            } else if (current instanceof ForStmt loop
                    && isWithin(node, loop.getBody())) {
                conditions.add(loop.getCompare().map(this::conditionText).orElse("for-loop"));
            } else if (current instanceof ForEachStmt loop
                    && isWithin(node, loop.getBody())) {
                conditions.add("hasNext(" + conditionText(loop.getIterable()) + ")");
            } else if (current instanceof TryStmt tryStatement) {
                if (isWithin(node, tryStatement.getTryBlock())) {
                    conditions.add("try");
                } else {
                    tryStatement.getCatchClauses().stream()
                            .filter(catchClause -> isWithin(node, catchClause))
                            .findFirst()
                            .ifPresent(catchClause -> conditions.add(
                                    "catch(" + catchClause.getParameter().getType().asString() + ")"
                            ));
                }
            }
        }
        return conditions;
    }

    // 作用：收集前置守卫；逻辑：在同级块中查找提前返回的 if，并把其条件取反作为后续语句前提。
    public List<String> precedingGuards(Node node) {
        List<String> conditions = new ArrayList<>();
        Node child = node;
        for (Node parent = node.getParentNode().orElse(null);
             parent != null && parent != boundary;
             child = parent, parent = parent.getParentNode().orElse(null)) {
            if (!(parent instanceof BlockStmt block) || !(child instanceof Statement currentStatement)) {
                continue;
            }
            for (Statement candidate : block.getStatements()) {
                if (candidate == currentStatement) {
                    break;
                }
                if (candidate instanceof IfStmt guard
                        && guard.getElseStmt().isEmpty()
                        && alwaysReturns(guard.getThenStmt())) {
                    conditions.add(negate(conditionText(guard.getCondition())));
                }
            }
        }
        return conditions;
    }

    // 作用：格式化条件表达式；逻辑：脱敏规范化后为空时使用占位文本。
    public String conditionText(Expression expression) {
        String value = context.normalizedExpression(expression);
        return value == null || value.isBlank() ? "condition" : value;
    }

    // 作用：取反条件；逻辑：用括号包裹后前置非运算。
    public String negate(String condition) {
        return "!(" + condition + ")";
    }

    // 作用：合并两个条件；逻辑：空值取另一侧，否则以逻辑与连接。
    public String combine(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null ? "" : right;
        }
        if (right == null || right.isBlank()) {
            return left;
        }
        return left + " && " + right;
    }

    // 作用：判断语句是否必然返回；逻辑：识别返回语句、末句返回的块与两分支都返回的 if。
    private boolean alwaysReturns(Statement statement) {
        if (statement instanceof ReturnStmt) {
            return true;
        }
        if (statement instanceof BlockStmt block) {
            List<Statement> statements = block.getStatements();
            return !statements.isEmpty() && alwaysReturns(statements.get(statements.size() - 1));
        }
        if (statement instanceof IfStmt branch) {
            return alwaysReturns(branch.getThenStmt())
                    && branch.getElseStmt().map(this::alwaysReturns).orElse(false);
        }
        return false;
    }

    // 作用：判断节点是否位于祖先内；逻辑：沿父链向上比对引用。
    private boolean isWithin(Node node, Node ancestor) {
        for (Node current = node; current != null; current = current.getParentNode().orElse(null)) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }
}
