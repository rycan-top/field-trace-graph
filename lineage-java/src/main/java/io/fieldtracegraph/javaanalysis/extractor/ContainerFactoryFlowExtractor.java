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
import java.util.LinkedHashMap;
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
            "List", "Set", "Map", "ImmutableMap", "ImmutableList", "ImmutableSet",
            "ImmutableSortedSet", "ImmutableSortedMap", "Collections", "Arrays",
            "Lists", "Sets", "Maps", "Optional", "CompletableFuture"
    );
    private static final Set<String> FACTORY_METHODS = Set.of(
            "of", "asList", "singletonList", "singleton", "unmodifiableList",
            "unmodifiableSet", "unmodifiableMap", "copyOf", "ofNullable",
            "newArrayList", "newLinkedList", "newHashSet", "newLinkedHashSet",
            "newHashMap", "newLinkedHashMap", "newTreeMap", "newTreeSet",
            "ofEntries", "singletonMap", "emptyList", "emptySet", "emptyMap",
            "completedFuture"
    );
    private static final Map<String, List<String>> TUPLE_SLOTS = tupleSlots();

    private final ValueEdgeEmitter edgeEmitter = new ValueEdgeEmitter();
    private final ValueNodeCatalog nodeCatalog = new ValueNodeCatalog();

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
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())) {
                continue;
            }
            List<String> tupleSlots = tupleSlotsFor(call);
            if (tupleSlots != null) {
                ordinal = emitTupleFactory(
                        source, scope, nodes, accumulator, context, call, tupleSlots, ordinal
                );
                continue;
            }
            if (!isFactory(call)) {
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

    /**
     * Tuple factories are value factories, not collections. Their components
     * are named fields, so modelling each position as a field slot lets
     * {@code Pair.of(a, b).getRight()}, {@code Triple.of(a, b, c).getMiddle()}
     * and {@code Tuple2.of(a, b).getT2()} continue through the same field
     * expansion used by JavaBean getters. Library-specific aliases are added
     * for tuples that expose both {@code tN} and {@code _N} accessors.
     */
    // 作用：展开元组工厂；逻辑：把各位置参数写入结果字段槽，并补齐 tN/_N 别名。
    private int emitTupleFactory(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            MethodCallExpr call,
            List<String> slots,
            int ordinal
    ) {
        String resultKey = context.key(call);
        String displayRoot = context.display(call);
        for (int index = 0; index < slots.size(); index++) {
            Expression argument = call.getArgument(index);
            String slot = slots.get(index);
            String targetKey = resultKey + "." + slot;
            ValueNodeRecord target = nodeCatalog.ensureField(
                    source, scope, nodes, accumulator, targetKey, displayRoot,
                    slot, "FIELD", call
            );
            for (ExpressionFlowSources.Source input : ExpressionFlowSources.collect(argument, context)) {
                ValueNodeRecord origin = nodeCatalog.ensure(
                        source, scope, nodes, accumulator, input.key(), null, argument
                );
                edgeEmitter.addEdge(
                        source, scope, accumulator, origin, target,
                        "FIELD_WRITE", "tuple " + slot, call, ordinal++,
                        io.fieldtracegraph.core.model.Provenance.FRAMEWORK_ADAPTER
                );
            }
            for (String alias : tupleAliases(slot)) {
                ValueNodeRecord aliasNode = nodeCatalog.ensureField(
                        source, scope, nodes, accumulator, resultKey + "." + alias,
                        displayRoot, alias, "FIELD", call
                );
                edgeEmitter.addEdge(
                        source, scope, accumulator, target, aliasNode,
                        "ASSIGNED_FROM", "tuple alias " + alias, call, ordinal++,
                        io.fieldtracegraph.core.model.Provenance.FRAMEWORK_ADAPTER
                );
            }
        }
        List<String> fieldNames = new ArrayList<>(slots);
        for (String slot : slots) {
            fieldNames.addAll(tupleAliases(slot));
        }
        return connectTupleAssignment(
                source, scope, nodes, accumulator, context, call,
                resultKey, displayRoot, fieldNames, ordinal
        );
    }

    // 作用：把元组字段同步到局部变量或赋值目标；逻辑：识别直接初始化和赋值，并重复写入各字段。
    private int connectTupleAssignment(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            MethodCallExpr call,
            String resultKey,
            String displayRoot,
            List<String> fieldNames,
            int ordinal
    ) {
        Node parent = call.getParentNode().orElse(null);
        String targetKey = null;
        if (parent instanceof VariableDeclarator variable) {
            targetKey = context.applyAlias(variable.getNameAsString());
        } else if (parent instanceof AssignExpr assignment) {
            targetKey = context.key(assignment.getTarget());
        }
        if (targetKey == null || targetKey.isBlank() || targetKey.equals(resultKey)) {
            return ordinal;
        }
        for (String slot : fieldNames) {
            String sourceKey = resultKey + "." + slot;
            String targetFieldKey = targetKey + "." + slot;
            ValueNodeRecord origin = nodeCatalog.ensureField(
                    source, scope, nodes, accumulator, sourceKey, displayRoot,
                    slot, "FIELD", call
            );
            ValueNodeRecord target = nodeCatalog.ensure(
                    source, scope, nodes, accumulator, targetFieldKey, "FIELD", call
            );
            edgeEmitter.addEdge(
                    source, scope, accumulator, origin, target,
                    "ASSIGNED_FROM", "tuple assignment", call, ordinal++,
                    io.fieldtracegraph.core.model.Provenance.FRAMEWORK_ADAPTER
            );
        }
        return ordinal;
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

    // 作用：解析元组工厂字段槽；逻辑：按 Pair/Triple、TupleN、JavaTuples 与 Map.entry 分类给出字段名。
    private List<String> tupleSlotsFor(MethodCallExpr call) {
        String method = call.getNameAsString();
        String receiver = simpleReceiver(call);
        int arity = call.getArguments().size();
        if ("entry".equals(method) && "Map".equals(receiver) && arity == 2) {
            return List.of("key", "value");
        }
        if (!Set.of("of", "with").contains(method)) {
            return null;
        }
        if ("Tuple".equals(receiver) && "of".equals(method) && arity >= 2) {
            return underscoreSlots(arity);
        }
        if ("Tuples".equals(receiver) && "of".equals(method) && arity >= 2) {
            return tSlots(arity);
        }
        List<String> slots = TUPLE_SLOTS.get(receiver);
        if (slots == null || slots.size() != arity) {
            return null;
        }
        if ("with".equals(method) && ("Pair".equals(receiver) || "Triple".equals(receiver))) {
            return valueSlots(arity);
        }
        return slots;
    }

    // 作用：生成元组字段别名；逻辑：tN 与 _N 两种常见访问器命名互相补齐。
    private List<String> tupleAliases(String slot) {
        if (slot.matches("t\\d+")) {
            return List.of("_" + slot.substring(1));
        }
        if (slot.matches("_\\d+")) {
            return List.of("t" + slot.substring(1));
        }
        return List.of();
    }

    // 作用：构造值位置槽；逻辑：从 value0 开始按参数个数生成。
    private static List<String> valueSlots(int count) {
        List<String> slots = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            slots.add("value" + index);
        }
        return List.copyOf(slots);
    }

    // 作用：构造 reactor 元组槽；逻辑：从 t1 开始按参数个数生成。
    private static List<String> tSlots(int count) {
        List<String> slots = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            slots.add("t" + (index + 1));
        }
        return List.copyOf(slots);
    }

    // 作用：构造 vavr 元组槽；逻辑：从 _1 开始按参数个数生成。
    private static List<String> underscoreSlots(int count) {
        List<String> slots = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            slots.add("_" + (index + 1));
        }
        return List.copyOf(slots);
    }

    // 作用：登记常见元组工厂字段槽；逻辑：按库的访问器命名建立类型到字段名列表的映射。
    private static Map<String, List<String>> tupleSlots() {
        Map<String, List<String>> slots = new LinkedHashMap<>();
        slots.put("Pair", List.of("left", "right"));
        slots.put("ImmutablePair", List.of("left", "right"));
        slots.put("MutablePair", List.of("left", "right"));
        slots.put("Triple", List.of("left", "middle", "right"));
        slots.put("ImmutableTriple", List.of("left", "middle", "right"));
        slots.put("MutableTriple", List.of("left", "middle", "right"));
        slots.put("KeyValue", List.of("key", "value"));
        slots.put("Quartet", valueSlots(4));
        slots.put("Quintet", valueSlots(5));
        slots.put("Sextet", valueSlots(6));
        slots.put("Septet", valueSlots(7));
        slots.put("Octet", valueSlots(8));
        slots.put("Ennead", valueSlots(9));
        slots.put("Decade", valueSlots(10));
        for (int arity = 2; arity <= 8; arity++) {
            slots.put("Tuple" + arity, tSlots(arity));
        }
        return Map.copyOf(slots);
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
