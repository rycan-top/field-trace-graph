package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.GuardConditionResolver;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.resolution.AccessorFieldIdentity;
import io.fieldtracegraph.javaanalysis.resolution.ExpressionTypeResolver;
import io.fieldtracegraph.javaanalysis.resolution.SourceTypeCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：提取 Stream 链式转换。
// 使用场景：map、filter、collect 等操作中的值流。

/**
 * Preserves fluent stream lineage: collection source, non-callback stage
 * arguments and the terminal result are all connected to the outer chain
 * expression. Lambda bodies remain owned by the normal statement extractors.
 */
public final class StreamFlowExtractor {
    private final ValueEdgeEmitter edgeEmitter = new ValueEdgeEmitter();
    private final ExpressionTypeResolver typeResolver;
    private final SourceTypeCatalog catalog;

    // 作用：注入类型目录；逻辑：保存目录并创建表达式类型解析器。
    public StreamFlowExtractor(SourceTypeCatalog catalog) {
        this.catalog = catalog;
        this.typeResolver = new ExpressionTypeResolver(catalog);
    }

    // 作用：提取流式调用流；逻辑：遍历完整流链，连接源容器元素并写入各阶段参数与映射结果字段。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        int ordinal = 0;
        GuardConditionResolver guards = new GuardConditionResolver(scope.callable(), context);
        for (MethodCallExpr call : scope.callable().findAll(MethodCallExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())
                    || !context.isStreamCall(call)
                    || context.isIntermediateStreamCall(call)) {
                continue;
            }
            JavaValueFlowContext.StreamChain chain = context.streamChain(call).orElse(null);
            if (chain == null) {
                continue;
            }
            String targetKey = context.key(call);
            String branchCondition = guards.conditionFor(call);
            ordinal = emitSource(source, scope, nodes, accumulator, context, guards,
                    chain.source(), targetKey, call, ordinal, branchCondition);
            for (MethodCallExpr stage : chain.stages()) {
                for (Expression argument : stage.getArguments()) {
                    Expression value = unwrap(argument);
                    if (value.isMethodReferenceExpr()) {
                        continue;
                    }
                    /*
                     * Collector factories wrap their callbacks one level
                     * deeper: {@code collect(groupingBy(k -> ...))}. Emit
                     * every lambda under the stage argument, not only a
                     * direct lambda argument.
                     */
                    for (LambdaExpr lambda : value.findAll(LambdaExpr.class)) {
                        ordinal = emitLambdaBody(
                                source, scope, nodes, accumulator, context,
                                lambda, targetKey, stage, ordinal
                        );
                    }
                    if (value.isLambdaExpr()) {
                        continue;
                    }
                    if ("collect".equals(stage.getNameAsString()) && isIdentityCollector(value)) {
                        continue;
                    }
                    ordinal = emitArgument(source, scope, nodes, accumulator, context, guards,
                            value, targetKey, call, stage.getNameAsString(), ordinal, branchCondition);
                }
                ordinal = emitMappedElementFields(source, scope, nodes, accumulator, context,
                        call, stage, ordinal);
            }
        }
    }

    /**
     * An expression-bodied lambda has no statement owner of its own, so the
     * normal assignment/return extractors never see it. Its reads still feed
     * the stage result: {@code collect(groupingBy(k -> k.getChannelId()))}
     * groups by that field. Emitting the read against the chain result keeps
     * the field traceable instead of leaving a node with no edges.
     */
    // 作用：提取流式 lambda 表达式体的读取流；逻辑：把体内字段读取连到该流阶段的结果值。
    private int emitLambdaBody(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            LambdaExpr lambda,
            String targetKey,
            MethodCallExpr stage,
            int ordinal
    ) {
        if (!lambda.getBody().isExpressionStmt()) {
            return ordinal;
        }
        Expression body = lambda.getBody().asExpressionStmt().getExpression();
        return edgeEmitter.emit(
                source, scope, nodes, accumulator, context,
                body, targetKey, false, lambda, ordinal,
                "stream stage " + stage.getNameAsString() + " lambda"
        );
    }

    /**
     * A stream map creates a new element. Constructor argument edges end at
     * the newly created object, so without this bridge a query for
     * {@code top10Users[*].expectPrice} cannot continue back to the lambda
     * source. The same bridge handles lambda-local maps that become stream
     * elements.
     */
    // 作用：连接映射结果字段；逻辑：解析 lambda 返回值，对构造结果与局部 Map 分别补齐元素级字段边。
    private int emitMappedElementFields(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            MethodCallExpr chainCall,
            MethodCallExpr stage,
            int ordinal
    ) {
        if (!Set.of(
                "map", "flatMap",
                "mapToInt", "mapToLong", "mapToDouble",
                "flatMapToInt", "flatMapToLong", "flatMapToDouble"
        ).contains(stage.getNameAsString())
                || stage.getArguments().size() != 1) {
            return ordinal;
        }
        if (stage.getArgument(0) instanceof MethodReferenceExpr reference) {
            return emitMappedReferenceFields(
                    source, scope, nodes, accumulator, context,
                    chainCall, reference, ordinal
            );
        }
        if (!(stage.getArgument(0) instanceof LambdaExpr lambda)) {
            return ordinal;
        }
        Expression result = lambdaResult(lambda).orElse(null);
        String resultElement = context.streamResultElementKey(chainCall);
        if (result == null || resultElement == null || resultElement.isBlank()) {
            return ordinal;
        }
        if (result instanceof ObjectCreationExpr creation) {
            return emitConstructorElementFields(
                    source, scope, nodes, accumulator, context,
                    creation, resultElement, ordinal
            );
        }
        if (result instanceof NameExpr name) {
            return emitMapElementEntries(
                    source, scope, nodes, accumulator, context,
                    lambda, name.getNameAsString(), resultElement, ordinal
            );
        }
        return ordinal;
    }

    /**
     * {@code map(Bean::getId)} has no lambda body, so the normal field-read
     * extractor never sees {@code getId()}. Project the accessor property from
     * the stream element onto the chain result, mirroring an explicit
     * {@code map(b -> b.getId())} body.
     */
    // 作用：展开方法引用的映射字段；逻辑：把源元素字段投影到流链结果的同名字段。
    private int emitMappedReferenceFields(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            MethodCallExpr chainCall,
            MethodReferenceExpr reference,
            int ordinal
    ) {
        if (!reference.getScope().isTypeExpr()) {
            // A bound reference such as bean::getFoo already carries its own
            // receiver; only Type::getter maps the stream element itself.
            return ordinal;
        }
        String property = AccessorFieldIdentity.propertyFromAccessor(
                reference.getIdentifier(), true
        );
        String sourceElement = context.streamElementKey(chainCall);
        String resultRoot = null;
        ValueNodeRecord chainNode = nodes.get(context.key(chainCall));
        if (chainNode != null) {
            resultRoot = chainNode.canonicalValue();
        }
        if (resultRoot == null || resultRoot.isBlank()) {
            resultRoot = context.display(chainCall);
            for (ValueNodeRecord node : nodes.values()) {
                if (resultRoot.equals(node.canonicalValue())) {
                    resultRoot = node.canonicalValue();
                    break;
                }
            }
        }
        if (property == null
                || sourceElement == null || sourceElement.isBlank()
                || resultRoot == null || resultRoot.isBlank()) {
            return ordinal;
        }
        ValueNodeRecord sourceNode = edgeEmitter.ensure(
                source, scope, nodes, accumulator, sourceElement, reference
        );
        ValueNodeRecord origin = edgeEmitter.ensure(
                source, scope, nodes, accumulator,
                sourceElement + "." + property, reference
        );
        ValueNodeRecord target = edgeEmitter.ensureField(
                source, scope, nodes, accumulator,
                resultRoot + "." + property, resultRoot, property, reference
        );
        if (!sourceNode.valueId().equals(origin.valueId())) {
            edgeEmitter.addEdge(
                    source, scope, accumulator, sourceNode, origin,
                    "CONTAINER_FLOW", "stream element field", reference, ordinal++
            );
        }
        edgeEmitter.addEdge(
                source, scope, accumulator, origin, target,
                "FIELD_READ", "stream stage map method reference", reference, ordinal++
        );
        return ordinal;
    }

    // 作用：展开构造器映射字段；逻辑：解析构造参数绑定，把创建对象的字段连到流结果元素字段。
    private int emitConstructorElementFields(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            ObjectCreationExpr creation,
            String resultElement,
            int ordinal
    ) {
        String typeQname = typeResolver.resolve(creation, scope, context);
        if (typeQname == null) {
            return ordinal;
        }
        List<String> argumentTypes = new ArrayList<>();
        for (Expression argument : creation.getArguments()) {
            argumentTypes.add(typeResolver.resolve(argument, scope, context));
        }
        Map<Integer, String> bindings = catalog.constructorFieldBindings(typeQname, argumentTypes);
        String creationKey = context.key(creation);
        for (String field : new java.util.LinkedHashSet<>(bindings.values())) {
            ValueNodeRecord origin = edgeEmitter.ensure(
                    source, scope, nodes, accumulator,
                    creationKey + "." + field, creation
            );
            ValueNodeRecord target = edgeEmitter.ensure(
                    source, scope, nodes, accumulator,
                    resultElement + "." + field, creation
            );
            edgeEmitter.addEdge(
                    source, scope, accumulator, origin, target,
                    "ASSIGNED_FROM", "stream map constructor field",
                    creation, ordinal++
            );
        }
        return ordinal;
    }

    // 作用：展开局部 Map 条目；逻辑：把 lambda 内 Map 写入的每个键值条目映射到结果元素同路径。
    private int emitMapElementEntries(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            LambdaExpr lambda,
            String localMap,
            String resultElement,
            int ordinal
    ) {
        for (MethodCallExpr write : lambda.findAll(MethodCallExpr.class)) {
            String sourceElement = context.mutationTarget(write);
            if (sourceElement == null || !sourceElement.startsWith(localMap + "[")) {
                continue;
            }
            ValueNodeRecord origin = edgeEmitter.ensure(
                    source, scope, nodes, accumulator, sourceElement, write
            );
            ValueNodeRecord target = edgeEmitter.ensure(
                    source, scope, nodes, accumulator,
                    resultElement + sourceElement.substring(localMap.length()), write
            );
            edgeEmitter.addEdge(
                    source, scope, accumulator, origin, target,
                    "WRITES_ELEMENT", "stream map local map entry",
                    write, ordinal++
            );
        }
        return ordinal;
    }

    // 作用：提取 lambda 返回值；逻辑：表达式体直接返回，语句体仅接受唯一的返回语句。
    private java.util.Optional<Expression> lambdaResult(LambdaExpr lambda) {
        if (lambda.getExpressionBody().isPresent()) {
            return lambda.getExpressionBody();
        }
        List<ReturnStmt> returns = lambda.findAll(ReturnStmt.class).stream()
                .filter(statement -> statement.findAncestor(LambdaExpr.class).orElse(null) == lambda)
                .toList();
        return returns.size() == 1 ? returns.get(0).getExpression() : java.util.Optional.empty();
    }

    // 作用：连接流源；逻辑：把源容器节点连到其元素节点，形成容器到元素的流边。
    private int emitSource(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            GuardConditionResolver guards,
            Expression sourceExpression,
            String targetKey,
            MethodCallExpr chainCall,
            int ordinal,
            String branchCondition
    ) {
        String sourceKey = context.key(sourceExpression);
        String elementKey = context.streamElementKey(chainCall);
        if (sourceKey != null && !sourceKey.isBlank() && elementKey != null) {
            ValueNodeRecord sourceNode = edgeEmitter.ensure(
                    source, scope, nodes, accumulator, sourceKey, chainCall
            );
            ValueNodeRecord elementNode = edgeEmitter.ensure(
                    source, scope, nodes, accumulator, elementKey, chainCall
            );
            if (!sourceNode.valueId().equals(elementNode.valueId())) {
                edgeEmitter.addEdge(source, scope, accumulator, sourceNode, elementNode,
                        "CONTAINER_FLOW", "stream source", chainCall, ordinal++);
            }
        }
        // The terminal chain is connected to the current SSA-like definition
        // by SequentialFlowExtractor. Re-emitting the bare root here would
        // reconnect a stream to an older or sibling assignment.
        return ordinal;
    }

    // 作用：写入阶段参数流；逻辑：以阶段名与分支条件合成操作文本后调用边发射器。
    private int emitArgument(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            GuardConditionResolver guards,
            Expression argument,
            String targetKey,
            MethodCallExpr chainCall,
            String stageName,
            int ordinal,
            String branchCondition
    ) {
        return edgeEmitter.emit(source, scope, nodes, accumulator, context,
                argument, targetKey, false, chainCall, ordinal,
                guards.withCondition(stageName, branchCondition));
    }

    // 作用：去除表达式包装；逻辑：循环剥离括号与强制类型转换。
    private Expression unwrap(Expression expression) {
        Expression value = expression;
        while (value instanceof com.github.javaparser.ast.expr.EnclosedExpr enclosed
                || value instanceof com.github.javaparser.ast.expr.CastExpr) {
            value = value instanceof com.github.javaparser.ast.expr.EnclosedExpr enclosed
                    ? enclosed.getInner()
                    : ((com.github.javaparser.ast.expr.CastExpr) value).getExpression();
        }
        return value;
    }

    // 作用：判断是否为恒等收集器；逻辑：识别无参的 Collectors.toList 与 toSet。
    private boolean isIdentityCollector(Expression expression) {
        if (!(expression instanceof MethodCallExpr call)
                || !call.getArguments().isEmpty()
                || !Set.of("toList", "toSet").contains(call.getNameAsString())) {
            return false;
        }
        return call.getScope()
                .map(scope -> Set.of("Collectors", "java.util.stream.Collectors")
                        .contains(scope.toString()))
                .orElse(true);
    }
}
