package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.JavaExpressionIdentity;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

// 作用：从源码生成值节点。
// 使用场景：遍历 AST 建立血缘图的顶点集合。

public final class ValueNodeExtractor {
    private final ValueNodeCatalog nodeCatalog = new ValueNodeCatalog();

    // 作用：提取值节点；逻辑：先建参数、lambda 参数、局部变量与返回值根，再为表达式和赋值目标建节点。
    public ExtractedValues extract(
            SourceFileRecord source,
            CallableScope scope,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        Map<String, ValueNodeRecord> latest = new LinkedHashMap<>();
        Map<String, List<ValueNodeRecord>> versions = new HashMap<>();
        for (Parameter parameter : scope.callable().getParameters()) {
            define(source, scope, latest, versions, parameter.getNameAsString(), "PARAMETER", parameter, accumulator, context);
        }
        // Lambda parameters are value roots owned by the enclosing callable.
        // Keep a distinct kind so call-argument binding never mistakes them for
        // method parameters of the callable.
        for (LambdaExpr lambda : scope.callable().findAll(LambdaExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(lambda, scope.callable())) {
                continue;
            }
            for (Parameter parameter : lambda.getParameters()) {
                define(source, scope, latest, versions, parameter.getNameAsString(),
                        "LAMBDA_PARAMETER", parameter, accumulator, context);
            }
        }
        for (VariableDeclarator variable : scope.callable().findAll(VariableDeclarator.class)) {
            if (CallableOwnership.belongsToCallable(variable, scope.callable())) {
                define(source, scope, latest, versions, variable.getNameAsString(), "LOCAL", variable, accumulator, context);
            }
        }
        if (!(scope.callable() instanceof ConstructorDeclaration)) {
            define(source, scope, latest, versions, "RETURN", "RETURN", scope.callable(), accumulator, context);
        }
        for (Expression expression : scope.callable().findAll(Expression.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(expression, scope.callable())) {
                continue;
            }
            if (expression instanceof VariableDeclarationExpr || expression instanceof AssignExpr) {
                continue;
            }
            if (expression instanceof MethodCallExpr call && context.isIntermediateStreamCall(call)) {
                continue;
            }
            if (expression instanceof MethodCallExpr call && context.isSetter(call)) {
                String fieldPath = context.writeFieldPath(call);
                define(
                        source,
                        scope,
                        latest,
                        versions,
                        "field-write:" + fieldPath,
                        "FIELD",
                        fieldPath,
                        call,
                        accumulator,
                        context
                );
                continue;
            }
            String key = context.key(expression);
            if (!key.isBlank() && !(expression instanceof NameExpr)) {
                if (key.endsWith("]") && latest.containsKey(key)) {
                    continue;
                }
                define(
                        source,
                        scope,
                        latest,
                        versions,
                        key,
                        nodeKind(key, scope.callable()),
                        expression,
                        accumulator,
                        context
                );
            }
        }
        for (AssignExpr assignment : scope.callable().findAll(AssignExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(assignment, scope.callable())) {
                continue;
            }
            String key = context.key(assignment.getTarget());
            if (!key.isBlank() && !latest.containsKey(key)) {
                define(source, scope, latest, versions, key, nodeKind(key, scope.callable()), assignment.getTarget(), accumulator, context);
            }
        }
        for (String elementRoot : context.elementRoots()) {
            nodeCatalog.ensure(
                    source,
                    scope,
                    latest,
                    accumulator,
                    elementRoot,
                    "CONTAINER_ELEMENT",
                    scope.callable()
            );
        }
        return new ExtractedValues(latest, versions);
    }

    // 作用：判定节点种类；逻辑：按字面量、容器元素、调用结果、引用与普通表达式分类。
    private String nodeKind(String key, com.github.javaparser.ast.body.CallableDeclaration<?> callable) {
        if (key.startsWith("literal:")) {
            return "CONSTANT";
        }
        if (key.contains("[*]") || key.matches(".*\\[[^]]+].*")) {
            return "CONTAINER_ELEMENT";
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
        return JavaExpressionIdentity.nodeKind(key, callable);
    }

    // 作用：以表达式展示值建节点；逻辑：先取展示文本再委托完整定义入口。
    private void define(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> latest,
            Map<String, List<ValueNodeRecord>> versions,
            String key,
            String kind,
            Node node,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        String display = node instanceof Expression expression
                ? context.display(expression)
                : key;
        define(source, scope, latest, versions, key, kind, display, node, accumulator, context);
    }

    // 作用：定义值节点并记录版本；逻辑：按同键序号生成 ID，拆分根名与访问路径后登记最新版本。
    private void define(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> latest,
            Map<String, List<ValueNodeRecord>> versions,
            String key,
            String kind,
            String display,
            Node node,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        if (key == null || key.isBlank()) {
            return;
        }
        List<ValueNodeRecord> existing = versions.computeIfAbsent(key, ignored -> new ArrayList<>());
        int definitionOrdinal = existing.size();
        String rootName;
        List<String> accessPath;
        String readableValue = display == null || display.isBlank() ? key : display;
        int dot = readableValue.indexOf('.');
        if (dot < 0) {
            rootName = readableValue;
            accessPath = List.of();
        } else {
            rootName = readableValue.substring(0, dot);
            accessPath = List.of(readableValue.substring(dot + 1).split("\\."));
        }
        int declarationOffset = node.getBegin().map(position -> position.line).orElse(1) * 10_000
                + node.getBegin().map(position -> position.column).orElse(1);
        ValueNodeRecord record = new ValueNodeRecord(
                Ids.sha256(scope.symbolId(), key, String.valueOf(definitionOrdinal)),
                scope.symbolId(),
                source.fileId(),
                kind,
                readableValue,
                rootName,
                accessPath,
                declarationOffset,
                definitionOrdinal,
                node instanceof Expression expression ? JavaExpressionIdentity.literalKindOf(expression) : null,
                node.getBegin().map(position -> position.line).orElse(1),
                node.getBegin().map(position -> position.column).orElse(1),
                node.getEnd().map(position -> position.line).orElse(1),
                node.getEnd().map(position -> position.column).orElse(1),
                source.relativePath(),
                node.getBegin().map(position -> position.line).orElse(1),
                node.getBegin().map(position -> position.column).orElse(1),
                node.getEnd().map(position -> position.line).orElse(1),
                node.getEnd().map(position -> position.column).orElse(1)
        );
        existing.add(record);
        latest.put(key, record);
        accumulator.addValueNode(record);
    }

    public record ExtractedValues(Map<String, ValueNodeRecord> latest, Map<String, List<ValueNodeRecord>> versions) {
    }
}
