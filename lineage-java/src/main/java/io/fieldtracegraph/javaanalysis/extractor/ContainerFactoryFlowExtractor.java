package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.flow.NodeFlowOwnership;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：提取容器工厂方法产生的值流。
// 使用场景：List.of、Arrays.asList、Map.of 等。

/**
 * Expands collection factories and collection constructors into element nodes.
 * Without this step {@code List.of(source)} is only a call result, so a later
 * {@code list[*].name} query cannot observe the source field.
 */
public final class ContainerFactoryFlowExtractor {
    private static final Set<String> FACTORY_TYPES = Set.of(
            "List", "Set", "Map", "ImmutableMap", "Collections", "Arrays"
    );
    private static final Set<String> FACTORY_METHODS = Set.of(
            "of", "asList", "singletonList", "singleton", "unmodifiableList",
            "unmodifiableSet", "unmodifiableMap", "copyOf"
    );

    private final ValueEdgeEmitter edgeEmitter = new ValueEdgeEmitter();

    // 作用：提取容器工厂流；逻辑：分别处理工厂调用、容器构造与容器赋值，生成元素与复制边。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        int ordinal = 0;
        for (MethodCallExpr call : scope.callable().findAll(MethodCallExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())
                    || !isFactory(call)) {
                continue;
            }
            ordinal = emitFactoryCall(source, scope, nodes, accumulator, context, call, ordinal);
        }
        for (ObjectCreationExpr creation : scope.callable().findAll(ObjectCreationExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(creation, scope.callable())
                    || !context.isContainerType(creation.getType().asString())) {
                continue;
            }
            ordinal = emitCreation(source, scope, nodes, accumulator, context, creation, ordinal);
        }
        for (VariableDeclarator variable : scope.callable().findAll(VariableDeclarator.class)) {
            if (!NodeFlowOwnership.belongsToOuterExecutable(variable, scope.callable())
                    || variable.getInitializer().isEmpty()) {
                continue;
            }
            ordinal = emitAssignmentCopy(source, scope, nodes, accumulator, context,
                    variable.getInitializer().orElseThrow(),
                    context.applyAlias(variable.getNameAsString()), variable, ordinal);
        }
        for (AssignExpr assignment : scope.callable().findAll(AssignExpr.class)) {
            if (!NodeFlowOwnership.belongsToOuterExecutable(assignment, scope.callable())) {
                continue;
            }
            ordinal = emitAssignmentCopy(source, scope, nodes, accumulator, context,
                    assignment.getValue(), context.key(assignment.getTarget()), assignment, ordinal);
        }
    }

    // 作用：展开工厂调用；逻辑：建立结果到元素槽的流，并把各参数来源写入元素槽。
    private int emitFactoryCall(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            MethodCallExpr call,
            int ordinal
    ) {
        String resultKey = context.key(call);
        String elementKey = context.elementKeyForContainer(resultKey);
        ValueNodeRecord result = edgeEmitter.ensure(
                source, scope, nodes, accumulator, resultKey, call
        );
        ValueNodeRecord element = edgeEmitter.ensure(
                source, scope, nodes, accumulator, elementKey, call
        );
        edgeEmitter.addEdge(source, scope, accumulator, result, element,
                "CONTAINER_FLOW", "container factory", call, ordinal++);

        boolean mapValueArguments = isMapFactory(call);
        List<Expression> values = mapValueArguments
                ? mapValues(call.getArguments())
                : call.getArguments();
        for (Expression argument : values) {
            if (argument.isLambdaExpr() || argument.isMethodReferenceExpr()) {
                continue;
            }
            for (ExpressionFlowSources.Source input : ExpressionFlowSources.collect(argument, context)) {
                ValueNodeRecord origin = edgeEmitter.ensure(
                        source, scope, nodes, accumulator, input.key(), argument
                );
                edgeEmitter.addEdge(source, scope, accumulator, origin, element,
                        "WRITES_ELEMENT", "factory " + call.getNameAsString(), call, ordinal++);
            }
        }
        return connectAssignedElement(source, scope, nodes, accumulator, context,
                call, element, ordinal);
    }

    // 作用：展开容器构造；逻辑：建立构造结果到元素槽的流，并写入或复制各参数元素。
    private int emitCreation(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            ObjectCreationExpr creation,
            int ordinal
    ) {
        String resultKey = context.key(creation);
        String elementKey = context.elementKeyForContainer(resultKey);
        ValueNodeRecord result = edgeEmitter.ensure(
                source, scope, nodes, accumulator, resultKey, creation
        );
        ValueNodeRecord element = edgeEmitter.ensure(
                source, scope, nodes, accumulator, elementKey, creation
        );
        edgeEmitter.addEdge(source, scope, accumulator, result, element,
                "CONTAINER_FLOW", "container constructor", creation, ordinal++);

        for (Expression argument : creation.getArguments()) {
            String argumentKey = context.key(argument);
            if (context.isContainerRoot(argumentKey)) {
                String sourceElement = context.elementKeyForContainer(argumentKey);
                ValueNodeRecord copied = edgeEmitter.ensure(
                        source, scope, nodes, accumulator, sourceElement, argument
                );
                edgeEmitter.addEdge(source, scope, accumulator, copied, element,
                        "COPIES_ELEMENTS", "constructor copy", creation, ordinal++);
                continue;
            }
            for (ExpressionFlowSources.Source input : ExpressionFlowSources.collect(argument, context)) {
                ValueNodeRecord origin = edgeEmitter.ensure(
                        source, scope, nodes, accumulator, input.key(), argument
                );
                edgeEmitter.addEdge(source, scope, accumulator, origin, element,
                        "WRITES_ELEMENT", "constructor", creation, ordinal++);
            }
        }
        return connectAssignedElement(source, scope, nodes, accumulator, context,
                creation, element, ordinal);
    }

    // 作用：连接赋值目标元素槽；逻辑：识别变量或赋值落点，生成生产者元素到目标元素的复制边。
    private int connectAssignedElement(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            Node producer,
            ValueNodeRecord producerElement,
            int ordinal
    ) {
        Node parent = producer.getParentNode().orElse(null);
        String targetKey = null;
        if (parent instanceof VariableDeclarator variable) {
            targetKey = context.applyAlias(variable.getNameAsString());
        } else if (parent instanceof AssignExpr assignment) {
            targetKey = context.key(assignment.getTarget());
        }
        if (targetKey == null || targetKey.isBlank() || !context.isContainerRoot(targetKey)) {
            return ordinal;
        }
        String targetElementKey = context.elementKeyForContainer(targetKey);
        ValueNodeRecord targetElement = edgeEmitter.ensure(
                source, scope, nodes, accumulator, targetElementKey, producer
        );
        edgeEmitter.addEdge(source, scope, accumulator, producerElement, targetElement,
                "COPIES_ELEMENTS", "assign elements", producer, ordinal++);
        return ordinal;
    }

    // 作用：处理容器赋值复制；逻辑：跳过已单独展开的工厂与构造，其余容器源生成元素复制边。
    private int emitAssignmentCopy(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            Expression sourceExpression,
            String targetKey,
            Node anchor,
            int ordinal
    ) {
        if (targetKey == null || targetKey.isBlank() || !context.isContainerRoot(targetKey)) {
            return ordinal;
        }
        if (sourceExpression instanceof MethodCallExpr call && isFactory(call)) {
            return ordinal;
        }
        if (sourceExpression instanceof ObjectCreationExpr creation
                && context.isContainerType(creation.getType().asString())) {
            return ordinal;
        }
        String sourceElementKey = elementKeyOf(sourceExpression, context);
        if (sourceElementKey == null || sourceElementKey.isBlank()) {
            return ordinal;
        }
        ValueNodeRecord sourceElement = edgeEmitter.ensure(
                source, scope, nodes, accumulator, sourceElementKey, sourceExpression
        );
        ValueNodeRecord targetElement = edgeEmitter.ensure(
                source, scope, nodes, accumulator, context.elementKeyForContainer(targetKey), anchor
        );
        edgeEmitter.addEdge(source, scope, accumulator, sourceElement, targetElement,
                "COPIES_ELEMENTS", "container assignment", anchor, ordinal++);
        return ordinal;
    }

    // 作用：取表达式的元素键；逻辑：流调用取流元素键，容器或调用结果补元素后缀，其余返回 null。
    private String elementKeyOf(Expression expression, JavaValueFlowContext context) {
        Expression value = expression;
        if (value instanceof MethodCallExpr call && context.isStreamCall(call)) {
            return context.streamElementKey(call);
        }
        String key = context.key(value);
        if (key == null || key.isBlank()) {
            return null;
        }
        if (context.isContainerRoot(key)
                || key.startsWith("call:")
                || key.startsWith("new:")) {
            return context.elementKeyForContainer(key);
        }
        return null;
    }

    // 作用：判断是否为容器工厂；逻辑：方法名与接收者类型同时命中白名单。
    private boolean isFactory(MethodCallExpr call) {
        if (!FACTORY_METHODS.contains(call.getNameAsString())) {
            return false;
        }
        String receiver = simpleReceiver(call);
        return FACTORY_TYPES.contains(receiver);
    }

    // 作用：判断是否为 Map 工厂；逻辑：接收者为 Map 或不可变 Map 类型。
    private boolean isMapFactory(MethodCallExpr call) {
        String receiver = simpleReceiver(call);
        return "Map".equals(receiver) || "ImmutableMap".equals(receiver);
    }

    // 作用：取简单接收者名；逻辑：去掉包名前缀，仅保留最后一段。
    private String simpleReceiver(MethodCallExpr call) {
        String receiver = call.getScope().map(Object::toString).orElse("");
        int dot = receiver.lastIndexOf('.');
        return dot < 0 ? receiver : receiver.substring(dot + 1);
    }

    // 作用：提取 Map 工厂的值参数；逻辑：按下标间隔取每对的第二个参数。
    private List<Expression> mapValues(List<Expression> arguments) {
        List<Expression> values = new ArrayList<>();
        for (int index = 1; index < arguments.size(); index += 2) {
            values.add(arguments.get(index));
        }
        return values;
    }
}
