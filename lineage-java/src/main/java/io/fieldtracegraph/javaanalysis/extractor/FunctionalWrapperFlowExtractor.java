package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.ReturnStmt;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.resolution.AccessorFieldIdentity;

import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

// 作用：提取 Optional/CompletableFuture 等函数式包装器的 lambda 字段流。
// 使用场景：Optional.map、CompletableFuture.thenApply 等回调中的字段投影。

/**
 * Optional and CompletableFuture are value wrappers. Their callback methods
 * are not ordinary external calls: {@code optional.map(v -> v.getPrice())}
 * produces a new wrapper element whose {@code price} field comes from the
 * original element. Modelling that bridge keeps field paths alive across the
 * JDK wrapper instead of stopping at a platform boundary.
 */
public final class FunctionalWrapperFlowExtractor {
    private static final Set<String> CALLBACK_METHODS = Set.of(
            "map", "flatMap", "filter", "thenApply", "thenCompose", "handle",
            "ifPresent", "ifPresentOrElse", "forEach"
    );
    private static final Set<String> WRAPPER_TYPES = Set.of(
            "Optional", "OptionalInt", "OptionalLong", "OptionalDouble",
            "CompletableFuture", "CompletionStage", "Future"
    );
    private static final Set<String> FACTORY_METHODS = Set.of(
            "of", "ofNullable", "completedFuture", "supplyAsync", "runAsync"
    );
    private static final Set<String> TERMINAL_METHODS = Set.of(
            "orElse", "orElseGet", "orElseThrow", "join", "get", "getNow"
    );
    private static final Set<String> CONSUMER_METHODS = Set.of(
            "ifPresent", "ifPresentOrElse", "forEach"
    );

    private final ValueEdgeEmitter edgeEmitter = new ValueEdgeEmitter();
    private final ValueNodeCatalog nodeCatalog = new ValueNodeCatalog();

    // 作用：提取函数式包装器回调；逻辑：连接源元素、lambda 参数和回调结果字段。
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
                    || !CALLBACK_METHODS.contains(call.getNameAsString())
                    || context.isStreamCall(call)) {
                continue;
            }
            LambdaExpr lambda = callbackLambda(call);
            Expression receiver = call.getScope().orElse(null);
            if (!isWrapperReceiver(receiver, context)) {
                continue;
            }
            String sourceElement = wrapperElementKey(receiver, context);
            String resultElement = wrapperResultElementKey(call, context);
            if (sourceElement == null || resultElement == null
                    || sourceElement.isBlank() || resultElement.isBlank()) {
                continue;
            }
            ValueNodeRecord sourceNode = nodeCatalog.ensure(
                    source, scope, nodes, accumulator, sourceElement, "CONTAINER_ELEMENT", call
            );
            String originPrefix = wrapperOriginPrefix(receiver, context, scope);
            MethodReferenceExpr reference = callbackReference(call);
            if (lambda == null && reference != null) {
                Set<String> suffixes = new LinkedHashSet<>();
                ordinal = emitReferenceResult(
                        source, scope, nodes, accumulator,
                        reference, sourceElement, originPrefix, resultElement,
                        call, suffixes, ordinal
                );
                ordinal = emitWrapperChainTargets(
                        source, scope, nodes, accumulator, context,
                        call, resultElement, suffixes, ordinal
                );
                continue;
            }
            if (lambda == null || lambda.getParameters().isEmpty()) {
                continue;
            }
            String parameter = lambda.getParameter(0).getNameAsString();
            String parameterKey = context.applyAlias(parameter);
            if (parameter.isBlank()) {
                continue;
            }
            ValueNodeRecord parameterNode = nodeCatalog.ensure(
                    source, scope, nodes, accumulator,
                    context.lambdaParameterKey(lambda, 0), "LAMBDA_PARAMETER", lambda
            );
            if (!sourceNode.valueId().equals(parameterNode.valueId())) {
                edgeEmitter.addEdge(
                        source, scope, accumulator, sourceNode, parameterNode,
                        "CONTAINER_FLOW", "functional wrapper parameter",
                        lambda, ordinal++
                );
            }
            if (CONSUMER_METHODS.contains(call.getNameAsString())) {
                ordinal = emitParameterFieldBridges(
                        source, scope, nodes, accumulator,
                        sourceElement, parameter, parameterKey,
                        lambda, ordinal
                );
                continue;
            }
            if ("filter".equals(call.getNameAsString())) {
                continue;
            }
            Expression result = lambdaResult(lambda);
            if (result == null) {
                continue;
            }
            Set<String> suffixes = new LinkedHashSet<>();
            ordinal = emitResult(
                    source, scope, nodes, accumulator, context,
                    result, parameter, parameterKey, sourceElement, originPrefix, resultElement,
                    call, suffixes, ordinal
            );
            ordinal = emitWrapperChainTargets(
                    source, scope, nodes, accumulator, context,
                    call, resultElement, suffixes, ordinal
            );
        }
    }

    // 作用：写入 lambda 结果字段；逻辑：把参数或源元素上的字段映射到结果元素同路径。
    private int emitResult(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            Expression result,
            String parameter,
            String parameterKey,
            String sourceElement,
            String originPrefix,
            String resultElement,
            Node anchor,
            Set<String> suffixes,
            int ordinal
    ) {
        List<ExpressionFlowSources.Source> inputs = ExpressionFlowSources.collect(result, context);
        for (ExpressionFlowSources.Source input : inputs) {
            String suffix = fieldSuffix(input.key(), parameter, parameterKey, sourceElement);
            if (suffix == null) {
                continue;
            }
            suffixes.add(suffix);
            /*
             * The wrapper element is transparent for field lineage. Projecting
             * the field suffix onto the source element as well as the lambda
             * parameter keeps bean.address reachable from the original object,
             * instead of making the path depend on a synthetic lambda node.
             */
            String targetKey = resultElement + suffix;
            String originKey = sourceElement + suffix;
            String semanticOriginKey = originPrefix == null
                    ? null
                    : originPrefix + suffix;
            ValueNodeRecord origin = nodeCatalog.ensure(
                    source, scope, nodes, accumulator, originKey, null, result
            );
            ValueNodeRecord target = nodeCatalog.ensure(
                    source, scope, nodes, accumulator, targetKey, "FIELD", result
            );
            if (origin.valueId().equals(target.valueId())) {
                continue;
            }
            edgeEmitter.addEdge(
                    source, scope, accumulator, origin, target,
                    "ASSIGNED_FROM", "functional wrapper result", anchor, ordinal++
            );
            if (semanticOriginKey != null
                    && !semanticOriginKey.equals(originKey)
                    && !semanticOriginKey.isBlank()) {
                ValueNodeRecord semanticOrigin = nodeCatalog.ensure(
                        source, scope, nodes, accumulator, semanticOriginKey, null, result
                );
                edgeEmitter.addEdge(
                        source, scope, accumulator, semanticOrigin, target,
                        "ASSIGNED_FROM", "functional wrapper origin", anchor, ordinal++
                );
            }
        }
        return ordinal;
    }

    /**
     * Consumer callbacks ({@code ifPresent}, {@code forEach}, ...) return no
     * value, so the mapped-field projection above cannot run. The lambda
     * parameter is still the same object as the wrapped element; bridge its
     * field reads back to the source element so {@code optional.ifPresent(v ->
     * use(v.getId()))} keeps {@code bean.id} reachable.
     */
    // 作用：连接消费者回调参数字段；逻辑：把参数下已登记的字段节点投影回源元素的同名字段。
    private int emitParameterFieldBridges(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            String sourceElement,
            String parameter,
            String parameterAlias,
            Node anchor,
            int ordinal
    ) {
        if (sourceElement == null || sourceElement.isBlank()
                || parameter == null || parameter.isBlank()) {
            return ordinal;
        }
        List<String> prefixes = parameterAlias == null || parameterAlias.isBlank()
                || parameterAlias.equals(parameter)
                ? List.of(parameter + ".")
                : List.of(parameter + ".", parameterAlias + ".");
        List<String> parameterFields = nodes.keySet().stream()
                .filter(key -> prefixes.stream().anyMatch(
                        prefix -> key.startsWith(prefix) && key.length() > prefix.length()))
                .toList();
        Set<String> bridged = new HashSet<>();
        for (String parameterField : parameterFields) {
            String suffix = parameterField.substring(
                    prefixes.stream()
                            .filter(parameterField::startsWith)
                            .mapToInt(String::length)
                            .max()
                            .orElse(parameter.length() + 1)
            );
            String originKey = sourceElement + "." + suffix;
            boolean originExisted = nodes.containsKey(originKey);
            ValueNodeRecord origin = nodeCatalog.ensure(
                    source, scope, nodes, accumulator, originKey, null, anchor
            );
            ValueNodeRecord target = nodes.get(parameterField);
            if (target == null || origin.valueId().equals(target.valueId())) {
                continue;
            }
            if (!bridged.add(origin.canonicalValue() + "->" + target.canonicalValue())) {
                continue;
            }
            if (!originExisted) {
                ValueNodeRecord sourceRoot = nodeCatalog.ensure(
                        source, scope, nodes, accumulator, sourceElement, null, anchor
                );
                edgeEmitter.addEdge(
                        source, scope, accumulator, sourceRoot, origin,
                        "FIELD_READ", "functional wrapper element field",
                        anchor, ordinal++
                );
            }
            edgeEmitter.addEdge(
                    source, scope, accumulator, origin, target,
                    "ASSIGNED_FROM", "functional wrapper parameter field",
                    anchor, ordinal++
            );
        }
        return ordinal;
    }

    // 作用：写入方法引用结果；逻辑：getter 方法引用按属性路径投影到包装器结果。
    private int emitReferenceResult(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            MethodReferenceExpr reference,
            String sourceElement,
            String originPrefix,
            String resultElement,
            Node anchor,
            Set<String> suffixes,
            int ordinal
    ) {
        String property = AccessorFieldIdentity.propertyFromAccessor(
                reference.getIdentifier(), true
        );
        if (property == null) {
            return ordinal;
        }
        String suffix = "." + property;
        suffixes.add(suffix);
        ValueNodeRecord origin = nodeCatalog.ensure(
                source, scope, nodes, accumulator, sourceElement + suffix, null, anchor
        );
        ValueNodeRecord target = nodeCatalog.ensure(
                source, scope, nodes, accumulator, resultElement + suffix, "FIELD", anchor
        );
        edgeEmitter.addEdge(
                source, scope, accumulator, origin, target,
                "ASSIGNED_FROM", "functional wrapper method reference", anchor, ordinal++
        );
        if (originPrefix != null && !originPrefix.isBlank()) {
            String semanticOriginKey = originPrefix + suffix;
            if (!semanticOriginKey.equals(sourceElement + suffix)) {
                ValueNodeRecord semanticOrigin = nodeCatalog.ensure(
                        source, scope, nodes, accumulator, semanticOriginKey, null, anchor
                );
                edgeEmitter.addEdge(
                        source, scope, accumulator, semanticOrigin, target,
                        "ASSIGNED_FROM", "functional wrapper method reference origin",
                        anchor, ordinal++
                );
            }
        }
        return ordinal;
    }

    /**
     * Preserves field paths through terminal wrapper methods and a direct
     * {@code return}. Without this projection a wrapped value ends at
     * {@code call:map(...).city} even though {@code return ...orElse(...)}
     * carries that field to the caller.
     */
    // 作用：把包装器字段投影到外层终结方法与返回值；逻辑：沿方法接收者链补边，最终写入 RETURN 字段。
    private int emitWrapperChainTargets(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            MethodCallExpr call,
            String resultElement,
            Set<String> suffixes,
            int ordinal
    ) {
        if (suffixes.isEmpty()) {
            return ordinal;
        }
        Node current = call;
        String element = resultElement;
        Node parent = current.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr || parent instanceof CastExpr) {
            current = parent;
            parent = current.getParentNode().orElse(null);
        }
        while (parent instanceof MethodCallExpr outer
                && isScopeOf(outer, current)
                && (CALLBACK_METHODS.contains(outer.getNameAsString())
                || TERMINAL_METHODS.contains(outer.getNameAsString()))) {
            String outerElement = context.key(outer);
            if (outerElement == null || outerElement.isBlank()) {
                break;
            }
            for (String suffix : suffixes) {
                ValueNodeRecord origin = nodeCatalog.ensure(
                        source, scope, nodes, accumulator, element + suffix, null, outer
                );
                ValueNodeRecord target = nodeCatalog.ensure(
                        source, scope, nodes, accumulator,
                        outerElement + suffix, "FIELD", outer
                );
                edgeEmitter.addEdge(
                        source, scope, accumulator, origin, target,
                        "ASSIGNED_FROM", "functional wrapper chain", outer, ordinal++
                );
            }
            element = outerElement;
            current = outer;
            parent = current.getParentNode().orElse(null);
            while (parent instanceof EnclosedExpr || parent instanceof CastExpr) {
                current = parent;
                parent = current.getParentNode().orElse(null);
            }
        }
        if (!(parent instanceof ReturnStmt statement)) {
            return ordinal;
        }
        for (String suffix : suffixes) {
            ValueNodeRecord origin = nodeCatalog.ensure(
                    source, scope, nodes, accumulator, element + suffix, null, statement
            );
            ValueNodeRecord target = nodeCatalog.ensure(
                    source, scope, nodes, accumulator,
                    "RETURN" + suffix, "FIELD", statement
            );
            edgeEmitter.addEdge(
                    source, scope, accumulator, origin, target,
                    "FIELD_WRITE", "returned wrapper field", statement, ordinal++
            );
        }
        return ordinal;
    }

    // 作用：判断外层调用是否以指定表达式为接收者；逻辑：剥离括号与强转后按对象身份比较。
    private boolean isScopeOf(MethodCallExpr outer, Node current) {
        return outer.getScope()
                .map(this::unwrap)
                .filter(value -> value == current)
                .isPresent();
    }

    // 作用：计算字段后缀；逻辑：参数或源元素的同路径字段转换为结果元素下的相对路径。
    private String fieldSuffix(
            String key,
            String parameter,
            String parameterKey,
            String sourceElement
    ) {
        if (key == null) {
            return null;
        }
        if (key.equals(parameter)
                || (parameterKey != null && key.equals(parameterKey))
                || key.equals(sourceElement)) {
            return "";
        }
        if (key.startsWith(parameter + ".")) {
            return key.substring(parameter.length());
        }
        if (parameterKey != null
                && !parameterKey.isBlank()
                && key.startsWith(parameterKey + ".")) {
            return key.substring(parameterKey.length());
        }
        if (key.startsWith(sourceElement + ".")) {
            return key.substring(sourceElement.length());
        }
        return null;
    }

    // 作用：判断接收者是否为函数式包装器；逻辑：按变量声明类型或工厂调用的简单类型识别。
    private boolean isWrapperReceiver(Expression receiver, JavaValueFlowContext context) {
        if (receiver == null) {
            return false;
        }
        String root = rootName(receiver);
        if (root != null && isWrapperType(context.declaredType(root))) {
            return true;
        }
        if (receiver instanceof MethodCallExpr call) {
            if (CALLBACK_METHODS.contains(call.getNameAsString())) {
                return true;
            }
            String owner = call.getScope()
                    .map(Object::toString)
                    .map(this::simpleName)
                    .orElse("");
            return WRAPPER_TYPES.contains(owner)
                    && FACTORY_METHODS.contains(call.getNameAsString());
        }
        return false;
    }

    // 作用：判断声明类型是否为函数式包装器；逻辑：简单类型名命中 Optional/Future 集合。
    private boolean isWrapperType(String type) {
        return type != null && WRAPPER_TYPES.contains(simpleType(type));
    }

    // 作用：生成源元素键；逻辑：包装器值键补元素后缀，形成 Optional/Future 的元素身份。
    private String wrapperElementKey(Expression receiver, JavaValueFlowContext context) {
        if (receiver instanceof NameExpr name
                && isWrapperType(context.declaredType(name.getNameAsString()))) {
            String key = context.key(receiver);
            if (key != null && !key.isBlank()) {
                return context.elementKeyForContainer(key);
            }
        }
        if (receiver instanceof MethodCallExpr call) {
            if ("filter".equals(call.getNameAsString())) {
                return call.getScope()
                        .map(scope -> wrapperElementKey(scope, context))
                        .orElse(null);
            }
            String owner = call.getScope()
                    .map(Object::toString)
                    .map(this::simpleName)
                    .orElse("");
            if (WRAPPER_TYPES.contains(owner)
                    && FACTORY_METHODS.contains(call.getNameAsString())
                    && !call.getArguments().isEmpty()) {
                String argument = context.key(call.getArgument(0));
                if (argument != null && !argument.isBlank()) {
                    return argument;
                }
            }
        }
        String key = context.key(receiver);
        if (key == null || key.isBlank()) {
            return null;
        }
        return key;
    }

    /**
     * Resolves the semantic path that the wrapper element represents. A chain
     * such as {@code Optional.of(bean).map(Bean::getAddress)} yields
     * {@code bean.address}; the next map's fields are therefore resolved
     * against the Address object, not against an opaque call identity.
     */
    // 作用：解析包装器元素的语义来源路径；逻辑：工厂取首参，回调在原路径上追加返回值字段。
    private String wrapperOriginPrefix(
            Expression receiver,
            JavaValueFlowContext context,
            CallableScope scope
    ) {
        if (receiver instanceof NameExpr name) {
            VariableDeclarator variable = scope.callable()
                    .findAll(VariableDeclarator.class).stream()
                    .filter(candidate -> candidate.getNameAsString()
                            .equals(name.getNameAsString()))
                    .findFirst()
                    .orElse(null);
            if (variable != null && variable.getInitializer().isPresent()) {
                Expression initializer = unwrap(variable.getInitializer().orElseThrow());
                if (isWrapperValue(initializer, context)) {
                    return wrapperOriginPrefix(initializer, context, scope);
                }
            }
            return context.key(receiver);
        }
        if (!(receiver instanceof MethodCallExpr call)) {
            return context.key(receiver);
        }
        String owner = call.getScope()
                .map(Object::toString)
                .map(this::simpleName)
                .orElse("");
        if (WRAPPER_TYPES.contains(owner)
                && FACTORY_METHODS.contains(call.getNameAsString())
                && !call.getArguments().isEmpty()) {
            return context.key(call.getArgument(0));
        }
        if ("filter".equals(call.getNameAsString())) {
            return call.getScope()
                    .map(value -> wrapperOriginPrefix(value, context, scope))
                    .orElse(null);
        }
        if (!CALLBACK_METHODS.contains(call.getNameAsString())) {
            return context.key(receiver);
        }
        String base = call.getScope()
                .map(value -> wrapperOriginPrefix(value, context, scope))
                .orElse(null);
        if (base == null || base.isBlank()) {
            return null;
        }
        String suffix = callbackResultSuffix(call, context);
        return suffix == null || suffix.isEmpty() ? base : base + suffix;
    }

    // 作用：判断表达式是否为包装器工厂或回调；逻辑：按接收者类型与回调方法名识别。
    private boolean isWrapperValue(Expression expression, JavaValueFlowContext context) {
        Expression value = unwrap(expression);
        if (!(value instanceof MethodCallExpr call)) {
            return false;
        }
        if (CALLBACK_METHODS.contains(call.getNameAsString())
                || TERMINAL_METHODS.contains(call.getNameAsString())) {
            return true;
        }
        String owner = call.getScope()
                .map(Object::toString)
                .map(this::simpleName)
                .orElse("");
        if (WRAPPER_TYPES.contains(owner)
                && FACTORY_METHODS.contains(call.getNameAsString())) {
            return true;
        }
        return isWrapperReceiver(call.getScope().orElse(null), context);
    }

    // 作用：计算回调返回值的字段后缀；逻辑：表达式体按首个来源解析，方法引用按 getter 属性解析。
    private String callbackResultSuffix(MethodCallExpr call, JavaValueFlowContext context) {
        MethodReferenceExpr reference = callbackReference(call);
        if (reference != null) {
            String property = AccessorFieldIdentity.propertyFromAccessor(
                    reference.getIdentifier(), true
            );
            return property == null ? null : "." + property;
        }
        LambdaExpr lambda = callbackLambda(call);
        if (lambda == null || lambda.getParameters().isEmpty()) {
            return null;
        }
        Expression result = lambdaResult(lambda);
        if (result == null) {
            return null;
        }
        List<ExpressionFlowSources.Source> inputs = ExpressionFlowSources.collect(result, context);
        if (inputs.size() != 1) {
            return null;
        }
        return fieldSuffix(
                inputs.get(0).key(),
                lambda.getParameter(0).getNameAsString(),
                context.applyAlias(lambda.getParameter(0).getNameAsString()),
                wrapperElementKey(call.getScope().orElse(null), context)
        );
    }

    // 作用：生成回调结果元素键；逻辑：优先落到赋值目标容器元素，否则落到调用结果元素。
    private String wrapperResultElementKey(MethodCallExpr call, JavaValueFlowContext context) {
        Node current = call;
        Node parent = current.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr || parent instanceof CastExpr) {
            current = parent;
            parent = current.getParentNode().orElse(null);
        }
        String target = null;
        if (parent instanceof VariableDeclarator variable
                && variable.getInitializer().map(this::unwrap)
                .filter(value -> value == call).isPresent()) {
            target = context.applyAlias(variable.getNameAsString());
        } else if (parent instanceof AssignExpr assignment
                && unwrap(assignment.getValue()) == call) {
            target = context.key(assignment.getTarget());
        }
        if (target != null && !target.isBlank()) {
            return target;
        }
        String key = context.key(call);
        return key == null || key.isBlank() ? null : key;
    }

    // 作用：查找回调 lambda；逻辑：取第一个 lambda 实参，忽略方法引用与非回调调用。
    private LambdaExpr callbackLambda(MethodCallExpr call) {
        for (Expression argument : call.getArguments()) {
            Expression value = unwrap(argument);
            if (value instanceof LambdaExpr lambda) {
                return lambda;
            }
        }
        return null;
    }

    // 作用：查找回调方法引用；逻辑：取第一个方法引用实参，用于 map(Type::getter) 形式。
    private MethodReferenceExpr callbackReference(MethodCallExpr call) {
        for (Expression argument : call.getArguments()) {
            Expression value = unwrap(argument);
            if (value instanceof MethodReferenceExpr reference) {
                return reference;
            }
        }
        return null;
    }

    // 作用：提取 lambda 返回值；逻辑：表达式体直接返回，语句体仅接受唯一 return。
    private Expression lambdaResult(LambdaExpr lambda) {
        if (lambda.getExpressionBody().isPresent()) {
            return lambda.getExpressionBody().orElseThrow();
        }
        List<ReturnStmt> returns = lambda.findAll(ReturnStmt.class).stream()
                .filter(statement -> statement.findAncestor(LambdaExpr.class).orElse(null) == lambda)
                .toList();
        return returns.size() == 1 ? returns.get(0).getExpression().orElse(null) : null;
    }

    // 作用：提取表达式根名；逻辑：剥离调用作用域、字段访问与下标后返回变量名。
    private String rootName(Expression expression) {
        Expression current = expression;
        while (true) {
            if (current instanceof EnclosedExpr enclosed) {
                current = enclosed.getInner();
            } else if (current instanceof CastExpr cast) {
                current = cast.getExpression();
            } else if (current instanceof MethodCallExpr call && call.getScope().isPresent()) {
                current = call.getScope().orElseThrow();
            } else if (current instanceof FieldAccessExpr field) {
                if (field.getScope().isThisExpr()) {
                    return field.getNameAsString();
                }
                current = field.getScope();
            } else if (current instanceof com.github.javaparser.ast.expr.ArrayAccessExpr access) {
                current = access.getName();
            } else {
                break;
            }
        }
        return current instanceof NameExpr name ? name.getNameAsString() : null;
    }

    // 作用：取简单类型名；逻辑：去掉泛型与包名前缀。
    private String simpleType(String type) {
        String value = type.trim();
        int generic = value.indexOf('<');
        if (generic >= 0) {
            value = value.substring(0, generic);
        }
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    // 作用：取简单名称；逻辑：保留最后一个点号后的文本。
    private String simpleName(String value) {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    // 作用：去除括号与强转；逻辑：循环剥离外层包装直到原始表达式。
    private Expression unwrap(Expression expression) {
        Expression value = expression;
        while (value instanceof EnclosedExpr enclosed || value instanceof CastExpr) {
            value = value instanceof EnclosedExpr
                    ? ((EnclosedExpr) value).getInner()
                    : ((CastExpr) value).getExpression();
        }
        return value;
    }
}
