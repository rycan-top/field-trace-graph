package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.JavaExpressionIdentity;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.GuardConditionResolver;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.resolution.BuilderChainClassifier;
import io.fieldtracegraph.javaanalysis.resolution.NonLineageCallFilter;

import java.util.Map;

// 作用：提取实参到形参、返回值到调用方的值流。
// 使用场景：跨方法追踪时保持链路连续。

/**
 * Emits argument and receiver dependencies for method and constructor results.
 * Cross-call parameter/return bridges are still owned by the query layer; this
 * extractor records the local value-flow facts needed to build those bridges.
 */
public final class CallValueFlowExtractor {
    private final NonLineageCallFilter nonLineageCallFilter = new NonLineageCallFilter();
    private final ValueNodeCatalog nodeCatalog = new ValueNodeCatalog();

    // 作用：提取调用输入流；逻辑：过滤访问器、Builder、容器与流调用后，为普通调用与对象创建补充依赖边。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        int statementOrdinal = 0;
        GuardConditionResolver guards = new GuardConditionResolver(scope.callable(), context);
        for (MethodCallExpr call : scope.callable().findAll(MethodCallExpr.class)) {
            boolean ignored = nonLineageCallFilter.shouldIgnore(call, context);
            if (ignored && nonLineageCallFilter.isRetainedControlCall(call, scope.callable())) {
                ignored = false;
            }
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())
                    || context.isGetter(call)
                    || context.isSetter(call)
                    || BuilderChainClassifier.isBuilderCall(call)
                    || ignored
                    || context.isContainerMutation(call)
                    || context.isStreamCall(call)
                    || isBeanCopyCall(call, context)) {
                continue;
            }
            statementOrdinal = addCallDependencies(
                    source, scope, nodes, accumulator, context, guards, call, statementOrdinal
            );
        }
        for (ObjectCreationExpr creation : scope.callable().findAll(ObjectCreationExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(creation, scope.callable())) {
                continue;
            }
            statementOrdinal = addCreationDependencies(
                    source, scope, nodes, accumulator, context, guards, creation, statementOrdinal
            );
        }
    }

    // 作用：补充调用依赖；逻辑：建立调用结果节点，并把接收者与各参数来源连到该节点。
    private int addCallDependencies(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            GuardConditionResolver guards,
            MethodCallExpr call,
            int statementOrdinal
    ) {
        // Container reads still have a real call site: the receiver and key
        // are dependencies of the read operation. Keep that call node separate
        // from the container element alias used for value propagation.
        String targetKey = context.isContainerRead(call)
                ? io.fieldtracegraph.javaanalysis.JavaExpressionIdentity.key(call)
                : context.key(call);
        if (targetKey == null || targetKey.isBlank()) {
            return statementOrdinal;
        }
        ValueNodeRecord target = nodeCatalog.ensure(
                source, scope, nodes, accumulator, targetKey, "CALL_RESULT", call
        );
        int ordinal = statementOrdinal;
        Expression receiver = meaningfulReceiver(call);
        if (receiver != null) {
            ordinal = addInputEdges(source, scope, nodes, accumulator, context,
                    guards, target, receiver, call, ordinal, "receiver");
        }
        for (Expression argument : call.getArguments()) {
            ordinal = addInputEdges(source, scope, nodes, accumulator, context,
                    guards, target, argument, call, ordinal, "argument");
        }
        return ordinal;
    }

    // 作用：补充构造依赖；逻辑：建立创建结果节点，并逐参数写入输入依赖边。
    private int addCreationDependencies(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            GuardConditionResolver guards,
            ObjectCreationExpr creation,
            int statementOrdinal
    ) {
        String targetKey = context.key(creation);
        if (targetKey == null || targetKey.isBlank()) {
            return statementOrdinal;
        }
        ValueNodeRecord target = nodeCatalog.ensure(
                source, scope, nodes, accumulator, targetKey, "CALL_RESULT", creation
        );
        int ordinal = statementOrdinal;
        for (Expression argument : creation.getArguments()) {
            ordinal = addInputEdges(source, scope, nodes, accumulator, context,
                    guards, target, argument, creation, ordinal, "constructor");
        }
        return ordinal;
    }

    // 作用：写入输入边；逻辑：展开表达式来源，跳过自环并合成操作与守卫条件后追加精确静态边。
    private int addInputEdges(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            GuardConditionResolver guards,
            ValueNodeRecord target,
            Expression expression,
            Node anchor,
            int statementOrdinal,
            String operation
    ) {
        int ordinal = statementOrdinal;
        String branchCondition = guards.conditionFor(anchor);
        for (ExpressionFlowSources.Source input : ExpressionFlowSources.collect(expression, context)) {
            ValueNodeRecord origin = nodeCatalog.ensure(
                    source, scope, nodes, accumulator,
                    input.key(), "EXPRESSION", expression
            );
            if (origin.valueId().equals(target.valueId())) {
                continue;
            }
            int line = anchor.getBegin().map(position -> position.line).orElse(1);
            int column = anchor.getBegin().map(position -> position.column).orElse(1);
            String evidenceId = Ids.sha256(scope.symbolId(), origin.valueId(), target.valueId(),
                    input.edgeType(), edgeOperation(operation, branchCondition, anchor, expression),
                    String.valueOf(line), String.valueOf(column));
            accumulator.addDataEdge(new DataEdgeRecord(
                    Ids.sha256(scope.symbolId(), origin.valueId(), target.valueId(),
                            input.edgeType(), String.valueOf(line), String.valueOf(column), String.valueOf(ordinal)),
                    scope.symbolId(),
                    origin.valueId(),
                    target.valueId(),
                    input.edgeType(),
                    edgeOperation(operation, branchCondition, anchor, expression),
                    "EXACT_STATIC",
                    evidenceId,
                    null,
                    null,
                    source.fileId(),
                    line,
                    column,
                    anchor.getEnd().map(position -> position.line).orElse(line),
                    anchor.getEnd().map(position -> position.column).orElse(column),
                    ordinal++
            ));
        }
        return ordinal;
    }

    // 作用：生成边操作描述；逻辑：接收者标注所属方法，参数保留表达式文本，并追加分支条件。
    private String edgeOperation(
            String operation,
            String branchCondition,
            Node anchor,
            Expression expression
    ) {
        if ("receiver".equals(operation)) {
            String name = anchor instanceof MethodCallExpr call
                    ? call.getNameAsString()
                    : "constructor";
            return branchCondition.isBlank()
                    ? "receiver of " + name
                    : "receiver of " + name + " && " + branchCondition;
        }
        if ("argument".equals(operation) || "constructor".equals(operation)) {
            String expressionText = operationText(expression);
            if (expressionText == null || expressionText.isBlank()) {
                return branchCondition;
            }
            return branchCondition.isBlank()
                    ? expressionText
                    : expressionText + " && " + branchCondition;
        }
        return branchCondition.isBlank() ? operation : branchCondition;
    }

    // 作用：取可读操作文本；逻辑：仅二元、一元、三元与创建表达式返回规范化文本。
    private String operationText(Expression expression) {
        if (expression instanceof com.github.javaparser.ast.expr.BinaryExpr
                || expression instanceof com.github.javaparser.ast.expr.UnaryExpr
                || expression instanceof com.github.javaparser.ast.expr.ConditionalExpr
                || expression instanceof ObjectCreationExpr) {
            return JavaExpressionIdentity.normalizedExpression(
                    expression
            );
        }
        return null;
    }

    // 作用：筛选有意义的接收者；逻辑：排除 this、super 与大写开头的静态类型限定。
    private Expression meaningfulReceiver(MethodCallExpr call) {
        Expression receiver = call.getScope().orElse(null);
        if (receiver == null || receiver.isThisExpr() || receiver.isSuperExpr()) {
            return null;
        }
        // A call result is produced by the invocation on its receiver. Keep
        // every instance receiver as a value-flow input so external or
        // unresolved calls such as provider.get(...) and
        // paymentService.fetchMerchantConfig(...) still have a producer.
        // Type-qualified static calls are not instance producers and are
        // deliberately excluded.
        if (receiver instanceof com.github.javaparser.ast.expr.NameExpr name
                && !name.getNameAsString().isEmpty()
                && Character.isUpperCase(name.getNameAsString().charAt(0))) {
            return null;
        }
        return receiver;
    }

    // 作用：判断是否为 Bean 拷贝调用；逻辑：识别 Spring、Hutool、BeanCopier 与常见拷贝工具。
    private boolean isBeanCopyCall(MethodCallExpr call, JavaValueFlowContext context) {
        String name = call.getNameAsString();
        String receiver = call.getScope().map(Object::toString).orElse("");
        if ("copyProperties".equals(name) && receiver.endsWith("BeanUtils")) {
            return true;
        }
        if ("copyProperties".equals(name)
                && call.getArguments().size() >= 2
                && (isHutoolBeanUtil(call, receiver)
                || hasStaticImport(call, "cn.hutool.core.bean.BeanUtil", "copyProperties"))) {
            return true;
        }
        if ("copyPropertiesValue".equals(name)
                && call.getArguments().size() >= 2
                && receiver.toLowerCase(java.util.Locale.ROOT).contains("beancopy")) {
            return true;
        }
        if (!"copy".equals(name)) {
            return false;
        }
        String lower = receiver.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("beancop")) {
            return true;
        }
        String declared = context.declaredType(receiver);
        return declared != null && context.simpleType(declared).equals("BeanCopier");
    }

    // 作用：判断是否为 Hutool 工具类；逻辑：简单名为 BeanUtil 且导入匹配，或直接使用全限定名。
    private boolean isHutoolBeanUtil(MethodCallExpr call, String receiver) {
        String simple = receiver;
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) {
            simple = simple.substring(dot + 1);
        }
        return ("BeanUtil".equals(simple)
                && hasImport(call, "cn.hutool.core.bean.BeanUtil"))
                || receiver.equals("cn.hutool.core.bean.BeanUtil");
    }

    // 作用：检查普通导入；逻辑：编译单元中存在指定类型的非静态导入。
    private boolean hasImport(MethodCallExpr call, String qualifiedType) {
        return call.findCompilationUnit()
                .map(unit -> unit.getImports().stream()
                        .anyMatch(importDeclaration -> !importDeclaration.isStatic()
                                && qualifiedType.equals(importDeclaration.getNameAsString())))
                .orElse(false);
    }

    // 作用：检查静态导入；逻辑：编译单元中存在指定类型与方法的静态导入。
    private boolean hasStaticImport(
            MethodCallExpr call,
            String qualifiedType,
            String methodName
    ) {
        return call.findCompilationUnit()
                .map(unit -> unit.getImports().stream()
                        .anyMatch(importDeclaration -> importDeclaration.isStatic()
                                && (qualifiedType + "." + methodName)
                                .equals(importDeclaration.getNameAsString())))
                .orElse(false);
    }
}
