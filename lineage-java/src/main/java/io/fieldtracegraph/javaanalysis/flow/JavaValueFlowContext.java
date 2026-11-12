package io.fieldtracegraph.javaanalysis.flow;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.JavaExpressionIdentity;
import io.fieldtracegraph.javaanalysis.resolution.AccessorFieldIdentity;
import io.fieldtracegraph.javaanalysis.resolution.JavaTypeNameResolver;
import io.fieldtracegraph.javaanalysis.resolution.JavaValueTypes;
import io.fieldtracegraph.javaanalysis.resolution.SourceTypeCatalog;
import io.fieldtracegraph.javaanalysis.resolution.TypeArgumentResolver;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 作用：提取器共享的类型、作用域与别名上下文。
// 使用场景：多个提取器协同分析同一方法时复用查询结果。

/**
 * Per-callable value-flow facts shared by the extractors: element aliases,
 * declared variable types and the rules for collection/map element reads.
 *
 * <p>This class deliberately does not create graph nodes. It only translates
 * Java expressions to stable logical value keys, so every extractor uses the
 * same alias and container semantics.</p>
 */
public final class JavaValueFlowContext {
    private static final Set<String> COLLECTION_TYPES = Set.of(
            "Collection", "Iterable", "List", "ArrayList", "LinkedList", "Set", "HashSet",
            "LinkedHashSet", "TreeSet", "SortedSet", "NavigableSet", "Queue", "Deque",
            "ArrayDeque", "PriorityQueue", "Stack", "Vector", "CopyOnWriteArrayList"
    );
    private static final Set<String> MAP_TYPES = Set.of(
            "Map", "HashMap", "LinkedHashMap", "TreeMap", "SortedMap", "NavigableMap",
            "ConcurrentMap", "ConcurrentHashMap", "Hashtable", "WeakHashMap", "EnumMap",
            "IdentityHashMap", "ConcurrentSkipListMap", "JSONObject"
    );
    private static final Set<String> WRAPPER_TYPES = Set.of(
            "Optional", "OptionalInt", "OptionalLong", "OptionalDouble", "CompletableFuture",
            "Future", "CompletionStage", "AtomicReference", "Supplier", "Callable"
    );
    private static final Pattern SAFE_MAP_KEY =
            Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_.$-]{0,63}");
    private static final Set<String> ELEMENT_READ_METHODS = Set.of(
            "get", "getOrDefault", "poll", "pollFirst", "pollLast", "peek", "peekFirst",
            "peekLast", "pop", "next", "first", "last", "iterator", "spliterator",
            "values", "keySet", "entrySet", "join", "getNow"
    );
    private static final Set<String> STREAM_STAGES = Set.of(
            "stream", "parallelStream", "filter", "map", "flatMap", "mapToInt", "mapToLong",
            "mapToDouble", "flatMapToInt", "flatMapToLong", "flatMapToDouble", "boxed",
            "asLongStream", "asDoubleStream", "sorted", "distinct", "limit", "skip", "peek",
            "takeWhile", "dropWhile", "sequential", "parallel", "unordered", "onClose",
            "collect", "toList", "toSet", "toArray", "forEach", "forEachOrdered", "findFirst",
            "findAny", "reduce", "min", "max", "sum", "count", "average", "summaryStatistics",
            "anyMatch", "allMatch", "noneMatch", "iterator", "spliterator", "close"
    );

    private final CallableDeclaration<?> callable;
    private final Map<String, String> elementAliases;
    private final Map<String, String> variableTypes;
    private final SourceTypeCatalog typeCatalog;
    private final String declaringTypeQname;

    // 作用：创建值流上下文；逻辑：保存可调用体、元素别名、变量类型与声明类型等只读事实。
    private JavaValueFlowContext(
            CallableDeclaration<?> callable,
            Map<String, String> elementAliases,
            Map<String, String> variableTypes,
            SourceTypeCatalog typeCatalog,
            String declaringTypeQname
    ) {
        this.callable = callable;
        this.elementAliases = Map.copyOf(elementAliases);
        this.variableTypes = Map.copyOf(variableTypes);
        this.typeCatalog = typeCatalog;
        this.declaringTypeQname = declaringTypeQname;
    }

    // 作用：由可调用体构建上下文；逻辑：自动取所在类型后委托完整构建入口。
    public static JavaValueFlowContext from(CallableDeclaration<?> callable) {
        return from(callable, callable.findAncestor(TypeDeclaration.class).orElse(null));
    }

    // 作用：由可调用体与声明类型构建上下文；逻辑：不提供类型目录，直接走完整入口。
    public static JavaValueFlowContext from(
            CallableDeclaration<?> callable,
            TypeDeclaration<?> declaringType
    ) {
        return from(callable, declaringType, null);
    }

    /**
     * Synthetic initializer scopes are not attached to the original type. The
     * declaring type therefore has to be supplied explicitly so field reads,
     * field container types and injected field names keep the same meaning as
     * they do inside normal methods.
     */
    // 作用：构建完整上下文；逻辑：收集参数、局部与字段类型，并推断 foreach 与 lambda 的元素别名。
    public static JavaValueFlowContext from(
            CallableDeclaration<?> callable,
            TypeDeclaration<?> declaringType,
            SourceTypeCatalog typeCatalog
    ) {
        Map<String, String> types = new LinkedHashMap<>();
        for (Parameter parameter : callable.getParameters()) {
            types.put(parameter.getNameAsString(), parameter.getType().asString());
        }
        for (VariableDeclarator variable : callable.findAll(VariableDeclarator.class)) {
            types.put(variable.getNameAsString(), variable.getType().asString());
        }
        if (declaringType != null) {
            for (FieldDeclaration field : declaringType.getFields()) {
                for (VariableDeclarator variable : field.getVariables()) {
                    String name = variable.getNameAsString();
                    String type = field.getElementType().asString();
                    types.put(name, type);
                    types.put("this." + name, type);
                    types.put(declaringType.getNameAsString() + "." + name, type);
                    declaringType.getFullyQualifiedName()
                            .ifPresent(qualifiedName -> types.put(qualifiedName + "." + name, type));
                }
            }
        }

        Map<String, String> aliases = new LinkedHashMap<>();
        List<LambdaAliasCandidate> lambdaAliases = new java.util.ArrayList<>();
        for (ForEachStmt loop : callable.findAll(ForEachStmt.class)) {
            String path = collectionPath(loop.getIterable(), types, typeCatalog);
            if (path != null && !loop.getVariable().getVariables().isEmpty()) {
                aliases.put(loop.getVariable().getVariables().get(0).getNameAsString(), path + "[*]");
            }
        }
        for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
            if (call.getScope().isEmpty()) {
                continue;
            }
            String path = collectionPath(call.getScope().orElseThrow(), types, typeCatalog);
            if (path == null || path.isBlank()) {
                continue;
            }
            for (Expression argument : call.getArguments()) {
                if (argument instanceof LambdaExpr lambda
                        && lambda.getParameters().size() == 1
                        && !lambda.getParameter(0).getNameAsString().isBlank()) {
                    recordLambdaParameterType(
                            types, typeCatalog, lambda.getParameter(0), path);
                    lambdaAliases.add(new LambdaAliasCandidate(
                            path,
                            lambda.getParameter(0).getNameAsString()
                    ));
                }
            }
        }
        for (LambdaAliasCandidate candidate : lambdaAliases) {
            if (isContainerPath(candidate.root(), types, typeCatalog)) {
                aliases.putIfAbsent(candidate.parameter(), candidate.root() + "[*]");
            }
        }
        String declaringTypeQname = declaringType == null
                ? null
                : declaringType.getFullyQualifiedName().orElse(declaringType.getNameAsString());
        return new JavaValueFlowContext(
                callable,
                aliases,
                types,
                typeCatalog,
                declaringTypeQname
        );
    }

    /**
     * Binds an implicitly typed lambda parameter to the element type of the
     * collection it iterates. Without this, {@code users.stream().map(u ->
     * u.getName())} leaves {@code u} as an unknown receiver and every call in
     * the lambda body becomes a boundary.
     */
    // 作用：记录 lambda 参数类型；逻辑：显式类型直接采用，隐式类型按容器路径推导元素类型。
    private static void recordLambdaParameterType(
            Map<String, String> types,
            SourceTypeCatalog typeCatalog,
            Parameter parameter,
            String containerPath
    ) {
        String name = parameter.getNameAsString();
        if (types.containsKey(name)) {
            return;
        }
        if (!parameter.getType().isUnknownType() && !parameter.getType().isVarType()) {
            types.put(name, parameter.getType().asString());
            return;
        }
        String elementType = elementTypeOfPath(containerPath, types, typeCatalog);
        if (elementType != null && !elementType.isBlank()) {
            types.put(name, elementType);
        }
    }

    // 作用：推导路径的元素类型；逻辑：先解析路径声明类型，再取泛型元素类型。
    private static String elementTypeOfPath(
            String path,
            Map<String, String> types,
            SourceTypeCatalog typeCatalog
    ) {
        String declared = declaredTypeOfPath(path, types, typeCatalog);
        return declared == null
                ? null
                : new TypeArgumentResolver(typeCatalog).elementType(declared);
    }

    // 作用：解析路径的声明类型；逻辑：直接查表，否则按成员递归解析并处理 values、keySet、entrySet 与字段类型。
    private static String declaredTypeOfPath(
            String path,
            Map<String, String> types,
            SourceTypeCatalog typeCatalog
    ) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String direct = types.get(path);
        if (direct != null) {
            return direct;
        }
        int dot = path.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String ownerPath = path.substring(0, dot);
        String member = path.substring(dot + 1);
        String ownerType = declaredTypeOfPath(ownerPath, types, typeCatalog);
        if (ownerType == null) {
            return null;
        }
        TypeArgumentResolver arguments = new TypeArgumentResolver(typeCatalog);
        if ("values".equals(member)) {
            return wrap("Collection", arguments.mapValueType(ownerType));
        }
        if ("keySet".equals(member)) {
            return wrap("Set", arguments.mapKeyType(ownerType));
        }
        if ("entrySet".equals(member)) {
            String key = arguments.mapKeyType(ownerType);
            String value = arguments.mapValueType(ownerType);
            if (key == null || value == null) {
                return null;
            }
            return "Map.Entry<" + key + "," + value + ">";
        }
        if (typeCatalog == null) {
            return null;
        }
        String qname = typeCatalog.resolveTypeName(JavaTypeNameResolver.normalize(ownerType));
        if (qname == null) {
            qname = JavaTypeNameResolver.normalize(ownerType);
        }
        return typeCatalog.fieldTypeInHierarchy(qname, member);
    }

    // 作用：包装容器泛型；逻辑：元素类型为空时返回 null，否则拼成容器加泛型参数。
    private static String wrap(String container, String element) {
        return element == null ? null : container + "<" + element + ">";
    }

    // 作用：把表达式转换为集合路径；逻辑：递归处理变量、this、字段、调用与下标，并折叠流中间操作与读取器。
    private static String collectionPath(
            Expression expression,
            Map<String, String> types,
            SourceTypeCatalog typeCatalog
    ) {
        Expression value = unwrap(expression);
        if (value instanceof NameExpr name) {
            return name.getNameAsString();
        }
        if (value instanceof ThisExpr) {
            return "this";
        }
        if (value instanceof FieldAccessExpr field) {
            String owner = collectionPath(field.getScope(), types, typeCatalog);
            return owner == null ? null : owner + "." + field.getNameAsString();
        }
        if (value instanceof MethodCallExpr call) {
            String owner = call.getScope()
                    .map(scope -> collectionPath(scope, types, typeCatalog))
                    .orElse(null);
            String methodName = call.getNameAsString();
            if (STREAM_STAGES.contains(methodName)) {
                return owner;
            }
            if (Set.of("values", "keySet", "entrySet").contains(methodName)) {
                return owner == null ? null : owner + "." + methodName;
            }
            if (!call.getArguments().isEmpty()) {
                return null;
            }
            String property = AccessorFieldIdentity.propertyFromAccessor(
                    methodName, true
            );
            if (property == null) {
                return null;
            }
            return owner == null ? null : owner + "." + property;
        }
        if (value instanceof ArrayAccessExpr access) {
            return collectionPath(access.getName(), types, typeCatalog);
        }
        return null;
    }

    // 作用：判断路径是否为容器；逻辑：逐段解析字段类型，最终类型属于集合、Map 或包装类型时返回 true。
    private static boolean isContainerPath(
            String path,
            Map<String, String> types,
            SourceTypeCatalog typeCatalog
    ) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String direct = types.get(path);
        if (direct != null) {
            return isContainerTypeName(direct);
        }
        int dot = path.indexOf('.');
        if (dot < 0 || typeCatalog == null) {
            return false;
        }
        String owner = path.substring(0, dot);
        String ownerType = types.get(owner);
        if (ownerType == null) {
            return false;
        }
        String qname = typeCatalog.resolveTypeName(
                JavaTypeNameResolver.normalize(ownerType)
        );
        if (qname == null) {
            qname = JavaTypeNameResolver.normalize(ownerType);
        }
        String current = path.substring(dot + 1);
        String[] fields = current.split("\\.");
        for (int index = 0; index < fields.length; index++) {
            String field = fields[index];
            if (index == fields.length - 1
                    && Set.of("values", "keySet", "entrySet").contains(field)
                    && isMapTypeName(qname)) {
                return true;
            }
            String fieldType = typeCatalog.fieldTypeInHierarchy(qname, field);
            if (fieldType == null) {
                return false;
            }
            if (index == fields.length - 1) {
                return isContainerTypeName(fieldType);
            }
            qname = typeCatalog.resolveTypeName(JavaTypeNameResolver.normalize(fieldType));
            if (qname == null) {
                return false;
            }
        }
        return false;
    }

    // 作用：判断 Map 类型名；逻辑：去掉泛型与包名后匹配内置 Map 集合或以 Map 结尾。
    private static boolean isMapTypeName(String declaredType) {
        if (declaredType == null) {
            return false;
        }
        String type = declaredType.trim();
        int generic = type.indexOf('<');
        if (generic >= 0) {
            type = type.substring(0, generic);
        }
        int dot = type.lastIndexOf('.');
        if (dot >= 0) {
            type = type.substring(dot + 1);
        }
        return MAP_TYPES.contains(type) || type.endsWith("Map");
    }

    // 作用：判断容器类型名；逻辑：去掉泛型与包名后匹配集合、Map 与包装类型。
    private static boolean isContainerTypeName(String declaredType) {
        if (declaredType == null) {
            return false;
        }
        String type = declaredType.trim();
        int generic = type.indexOf('<');
        if (generic >= 0) {
            type = type.substring(0, generic);
        }
        int dot = type.lastIndexOf('.');
        if (dot >= 0) {
            type = type.substring(dot + 1);
        }
        return COLLECTION_TYPES.contains(type)
                || MAP_TYPES.contains(type)
                || WRAPPER_TYPES.contains(type);
    }

    // 作用：读取当前可调用体；逻辑：返回构建时保存的语法节点。
    public CallableDeclaration<?> callable() {
        return callable;
    }

    // 作用：把表达式转逻辑值键；逻辑：按类型引用、下标、字段、this/super、容器读取、访问器与普通调用分类编码。
    public String key(Expression expression) {
        Expression value = unwrap(expression);
        if (value instanceof ClassExpr || value instanceof TypeExpr) {
            return "reference:" + JavaExpressionIdentity.normalizedExpression(value);
        }
        if (value instanceof ArrayAccessExpr access) {
            String root = key(access.getName());
            if (isMapRoot(root)) {
                return root + "[" + mapKey(access.getIndex()) + "]";
            }
            return root + "[*]";
        }
        if (value instanceof FieldAccessExpr field) {
            if (!isBusinessFieldAccess(field)) {
                return "reference:" + JavaExpressionIdentity.normalizedExpression(field);
            }
            return applyAlias(key(field.getScope()) + "." + field.getNameAsString());
        }
        if (value instanceof com.github.javaparser.ast.expr.ThisExpr) {
            return "this";
        }
        if (value instanceof com.github.javaparser.ast.expr.SuperExpr) {
            return "super";
        }
        if (value instanceof MethodCallExpr call) {
            String containerRead = containerReadKey(call);
            if (containerRead != null) {
                return containerRead;
            }
            String fieldPath = readFieldPath(call);
            if (fieldPath != null) {
                return fieldPath;
            }
            // Rejected accessors and ordinary calls must keep call identity.
            // Falling back to JavaExpressionIdentity.key(call) would re-enable
            // the name-only getter rule for calls such as service.getTime().
            return applyAlias(JavaExpressionIdentity.callKey(call));
        }
        return applyAlias(JavaExpressionIdentity.key(value));
    }

    // 作用：生成展示文本；逻辑：优先返回语义键，普通调用仅显示方法名与参数个数。
    public String display(Expression expression) {
        Expression value = unwrap(expression);
        String normalized = key(value);
        if (!normalized.equals(applyAlias(JavaExpressionIdentity.key(value)))
                || value instanceof ArrayAccessExpr
                || value instanceof FieldAccessExpr
                || value instanceof NameExpr) {
            return normalized;
        }
        if (value instanceof MethodCallExpr call
                && containerReadKey(call) != null) {
            return normalized;
        }
        if (value instanceof MethodCallExpr call) {
            if (readFieldPath(call) != null) {
                return normalized;
            }
            return "call:" + call.getNameAsString() + "(" + call.getArguments().size() + ")";
        }
        return applyAlias(JavaExpressionIdentity.display(value));
    }

    /**
     * Resolves a JavaBean-style read only when the receiver shape proves that
     * the property is a field. Source types are checked against their declared
     * field set; JDK value methods remain value transformations, not fields.
     */
    // 作用：解析读取字段路径；逻辑：仅当名称或接收者类型能证明是字段访问时返回限定路径。
    public String readFieldPath(MethodCallExpr call) {
        if (AccessorFieldIdentity.belongsToObjectIntrospectionChain(call)) {
            return null;
        }
        String property = AccessorFieldIdentity.propertyFromAccessor(call.getNameAsString(), true);
        if (property == null && call.getArguments().isEmpty()) {
            property = directFieldProperty(call, true);
        }
        if (property == null || !call.getArguments().isEmpty()
                || !hasAccessorEvidence(call, property)) {
            return null;
        }
        return qualifyAccessor(call, property);
    }

    // 作用：解析写入字段路径；逻辑：要求单参数且具备字段证据，返回接收者加属性名。
    public String writeFieldPath(MethodCallExpr call) {
        if (AccessorFieldIdentity.belongsToObjectIntrospectionChain(call)) {
            return null;
        }
        String property = AccessorFieldIdentity.propertyFromAccessor(call.getNameAsString(), false);
        if (property == null && call.getArguments().size() == 1) {
            property = directFieldProperty(call, false);
        }
        if (property == null || call.getArguments().size() != 1
                || !hasAccessorEvidence(call, property)) {
            return null;
        }
        return qualifyAccessor(call, property);
    }

    // 作用：判断是否为 getter；逻辑：能解析出读取字段路径即视为 getter。
    public boolean isGetter(MethodCallExpr call) {
        return readFieldPath(call) != null;
    }

    // 作用：判断是否为 setter；逻辑：能解析出写入字段路径即视为 setter。
    public boolean isSetter(MethodCallExpr call) {
        return writeFieldPath(call) != null;
    }

    /**
     * A Java field-access AST also represents package names, qualified type
     * names, static class literals and static constant paths. Those are
     * references, not fields owned by a value object. Keeping that distinction
     * in the shared identity prevents pseudo fields such as {@code Map.class}
     * or {@code org.apache} from entering field lineage.
     */
    // 作用：判断是否为业务字段访问；逻辑：排除 class 字面量，并按作用域形态与变量声明判断是否真实字段。
    public boolean isBusinessFieldAccess(FieldAccessExpr field) {
        if (field == null || "class".equals(field.getNameAsString())) {
            return false;
        }
        Expression scope = unwrap(field.getScope());
        if (scope instanceof ThisExpr) {
            return hasField(field.getNameAsString());
        }
        if (scope instanceof SuperExpr) {
            return true;
        }
        if (scope instanceof FieldAccessExpr parent) {
            return isBusinessFieldAccess(parent);
        }
        if (scope instanceof MethodCallExpr
                || scope instanceof ObjectCreationExpr
                || scope instanceof CastExpr
                || scope instanceof ArrayAccessExpr) {
            return true;
        }
        if (scope instanceof NameExpr name) {
            String root = name.getNameAsString();
            if (variableTypes.containsKey(root) || variableTypes.containsKey("this." + root)) {
                return true;
            }
            // A non-declared root is normally a package or a static type. It
            // is safer to leave it as a reference than to invent a value field.
            return false;
        }
        return true;
    }

    /**
     * Human-readable and literal-safe expression text for summaries and branch
     * conditions. Element aliases are expanded after rendering so conditions
     * keep the same collection-level identity as the data edges.
     */
    // 作用：规范化表达式文本；逻辑：脱敏压缩后按词边界展开元素别名，空文本回退为占位。
    public String normalizedExpression(Expression expression) {
        Expression value = unwrap(expression);
        String text = JavaExpressionIdentity.normalizedExpression(value);
        for (Map.Entry<String, String> entry : elementAliases.entrySet()) {
            String name = Pattern.quote(entry.getKey());
            Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_$])" + name + "(?![A-Za-z0-9_$])");
            Matcher matcher = pattern.matcher(text);
            text = matcher.replaceAll(Matcher.quoteReplacement(entry.getValue()));
        }
        return text.isBlank() ? "condition" : text;
    }

    // 作用：应用元素别名；逻辑：仅替换表达式根名，保留后续字段路径。
    public String applyAlias(String value) {
        if (value == null || value.isBlank() || elementAliases.isEmpty()) {
            return value;
        }
        int separator = value.indexOf('.');
        if (separator < 0) {
            return elementAliases.getOrDefault(value, value);
        }
        if (separator == 0) {
            return value;
        }
        String head = value.substring(0, separator);
        String mapped = elementAliases.get(head);
        return mapped == null ? value : mapped + value.substring(separator);
    }

    // 作用：对字段路径应用别名；逻辑：直接复用通用别名替换。
    public String applyAliasToFieldPath(String fieldPath) {
        return applyAlias(fieldPath);
    }

    // 作用：读取元素根集合；逻辑：返回全部别名目标并去重。
    public Set<String> elementRoots() {
        return new LinkedHashSet<>(elementAliases.values());
    }

    // 作用：判断是否为容器读取；逻辑：能解析出容器读取键即返回 true。
    public boolean isContainerRead(MethodCallExpr call) {
        return containerReadKey(call) != null;
    }

    // 作用：解析容器读取键；逻辑：按 values、keySet、entrySet、Map 取值与普通元素读取生成键。
    public String containerReadKey(MethodCallExpr call) {
        if (!ELEMENT_READ_METHODS.contains(call.getNameAsString()) || call.getScope().isEmpty()) {
            return null;
        }
        String root = rootName(call.getScope().orElseThrow());
        if (root == null || !isContainerRoot(root)) {
            return null;
        }
        if (Set.of("values", "keySet", "entrySet").contains(call.getNameAsString())) {
            return root + "." + call.getNameAsString() + "[*]";
        }
        if (isMapRoot(root) && call.getArguments().size() == 1) {
            return root + "[" + mapKey(call.getArgument(0)) + "]";
        }
        return root + "[*]";
    }

    /**
     * Stream chains preserve the collection they came from. The terminal call
     * is still a value expression, but its logical origin is the source
     * collection element, not an opaque call-site identity.
     */
    // 作用：解析流元素键；逻辑：回溯流链源头，把容器源转换为元素键，未知源返回 null。
    public String streamElementKey(MethodCallExpr call) {
        if (!STREAM_STAGES.contains(call.getNameAsString())) {
            return null;
        }
        Expression source = streamChain(call)
                .map(StreamChain::source)
                .map(JavaValueFlowContext::unwrap)
                .orElse(null);
        if (source == null) {
            return null;
        }
        String sourceKey = key(source);
        if (sourceKey == null || sourceKey.isBlank()
                || sourceKey.startsWith("call:")
                || sourceKey.startsWith("new:")) {
            return null;
        }
        if (sourceKey.endsWith("[*]") || sourceKey.matches(".*\\[[^]]+]$")) {
            return sourceKey;
        }
        return isContainerRoot(sourceKey) || isContainerPath(sourceKey, variableTypes, typeCatalog)
                ? elementKeyForContainer(sourceKey)
                : null;
    }

    /**
     * Returns the concrete element identity of a stream chain result. A chain
     * assigned to {@code tabs} produces {@code tabs[*]}; a chain returned
     * directly keeps the source element identity.
     */
    // 作用：解析流结果元素键；逻辑：若链结果赋给容器变量则用该容器元素键，否则回退到源元素键。
    public String streamResultElementKey(MethodCallExpr chainCall) {
        com.github.javaparser.ast.Node current = chainCall;
        com.github.javaparser.ast.Node parent = current.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr || parent instanceof CastExpr) {
            current = parent;
            parent = current.getParentNode().orElse(null);
        }
        String containerKey = null;
        if (parent instanceof VariableDeclarator variable
                && variable.getInitializer().map(JavaValueFlowContext::unwrap)
                .filter(value -> value == chainCall).isPresent()) {
            containerKey = applyAlias(variable.getNameAsString());
        } else if (parent instanceof com.github.javaparser.ast.expr.AssignExpr assignment
                && unwrap(assignment.getValue()) == chainCall) {
            containerKey = key(assignment.getTarget());
        }
        if (containerKey != null && isContainerRoot(containerKey)) {
            return elementKeyForContainer(containerKey);
        }
        return streamElementKey(chainCall);
    }

    // 作用：判断是否为流调用；逻辑：能识别出完整流链即返回 true。
    public boolean isStreamCall(MethodCallExpr call) {
        return streamChain(call).isPresent();
    }

    /**
     * Returns true while the call is an intermediate stage of a fluent stream
     * chain. Intermediate calls are represented by the outer terminal call,
     * not as independent value nodes.
     */
    // 作用：判断是否为流中间调用；逻辑：父调用以它为作用域且自身可构成流链时返回 true。
    public boolean isIntermediateStreamCall(MethodCallExpr call) {
        com.github.javaparser.ast.Node current = call;
        while (current.getParentNode()
                .filter(parent -> parent instanceof EnclosedExpr || parent instanceof CastExpr)
                .isPresent()) {
            current = current.getParentNode().orElseThrow();
        }
        if (!(current.getParentNode().orElse(null) instanceof MethodCallExpr parent)) {
            return false;
        }
        return parent.getScope().filter(scope -> unwrap(scope) == call).isPresent()
                && streamChain(parent).isPresent();
    }

    // 作用：识别流链；逻辑：沿作用域逐级回溯，遇到 stream 或 parallelStream 时返回源与各阶段。
    public Optional<StreamChain> streamChain(MethodCallExpr call) {
        java.util.List<MethodCallExpr> stages = new java.util.ArrayList<>();
        MethodCallExpr current = call;
        while (true) {
            stages.add(current);
            if (Set.of("stream", "parallelStream").contains(current.getNameAsString())
                    && current.getArguments().isEmpty()) {
                return current.getScope()
                        .map(JavaValueFlowContext::unwrap)
                        .map(source -> new StreamChain(source, List.copyOf(stages)));
            }
            if (!STREAM_STAGES.contains(current.getNameAsString()) || current.getScope().isEmpty()) {
                return Optional.empty();
            }
            Expression scope = unwrap(current.getScope().orElseThrow());
            if (!(scope instanceof MethodCallExpr previous)) {
                return Optional.empty();
            }
            current = previous;
        }
    }

    // 作用：取流链源头；逻辑：识别流链后返回其源表达式。
    public Optional<Expression> streamSource(MethodCallExpr call) {
        return streamChain(call).map(StreamChain::source);
    }

    // 作用：判断是否为容器写操作；逻辑：能解析出单元素写入或批量复制目标即返回 true。
    public boolean isContainerMutation(MethodCallExpr call) {
        return mutationTarget(call) != null || bulkCopyTarget(call) != null;
    }

    // 作用：解析容器写入目标；逻辑：Map 的 put 用键定位，集合的 add、set、offer、push 定位元素槽。
    public String mutationTarget(MethodCallExpr call) {
        if (call.getScope().isEmpty()) {
            return null;
        }
        String root = rootName(call.getScope().orElseThrow());
        if (root == null || !isContainerRoot(root)) {
            return null;
        }
        String name = call.getNameAsString();
        int arity = call.getArguments().size();
        if (isMapRoot(root)) {
            if ("put".equals(name) && arity == 2) {
                return root + "[" + mapKey(call.getArgument(0)) + "]";
            }
            return null;
        }
        if ("add".equals(name) && (arity == 1 || arity == 2)) {
            return root + "[*]";
        }
        if (("set".equals(name) || "offer".equals(name) || "push".equals(name)) && arity >= 1) {
            return root + "[*]";
        }
        return null;
    }

    // 作用：解析批量复制目标；逻辑：addAll 与 putAll 且单参数时返回容器元素槽。
    public String bulkCopyTarget(MethodCallExpr call) {
        if (call.getScope().isEmpty()) {
            return null;
        }
        String root = rootName(call.getScope().orElseThrow());
        if (root == null || !isContainerRoot(root)) {
            return null;
        }
        if (!Set.of("addAll", "putAll").contains(call.getNameAsString())
                || call.getArguments().size() != 1) {
            return null;
        }
        return root + "[*]";
    }

    // 作用：生成容器元素键；逻辑：已带元素标记时原样返回，否则补元素后缀。
    public String elementKeyForContainer(String containerKey) {
        return containerKey.endsWith("[*]") ? containerKey : containerKey + "[*]";
    }

    // 作用：生成集合元素键；逻辑：根名后追加元素标记。
    public String collectionElementKey(String root) {
        return root + "[*]";
    }

    // 作用：生成 Map 元素键；逻辑：根名后追加规范化的键表达式。
    public String mapElementKey(String root, Expression key) {
        return root + "[" + mapKey(key) + "]";
    }

    // 作用：判断根是否为 Map；逻辑：查询根变量的声明类型。
    public boolean isMapRoot(String root) {
        return isMapType(variableTypes.get(root));
    }

    // 作用：判断根是否为集合；逻辑：查询根变量的声明类型。
    public boolean isCollectionRoot(String root) {
        return isCollectionType(variableTypes.get(root));
    }

    // 作用：判断集合类型；逻辑：简单类型名属于内置集合集合时返回 true。
    public boolean isCollectionType(String declaredType) {
        return COLLECTION_TYPES.contains(simpleType(declaredType));
    }

    // 作用：判断 Map 类型；逻辑：简单类型名属于内置 Map 集合时返回 true。
    public boolean isMapType(String declaredType) {
        return MAP_TYPES.contains(simpleType(declaredType));
    }

    // 作用：判断包装类型；逻辑：简单类型名属于 Optional、Future 等包装集合时返回 true。
    public boolean isWrapperType(String declaredType) {
        return WRAPPER_TYPES.contains(simpleType(declaredType));
    }

    // 作用：判断容器类型；逻辑：集合、Map 或包装类型任一成立即返回 true。
    public boolean isContainerType(String declaredType) {
        return isCollectionType(declaredType)
                || isMapType(declaredType)
                || isWrapperType(declaredType);
    }

    // 作用：判断根是否为容器；逻辑：查询根变量的声明类型并判断容器种类。
    public boolean isContainerRoot(String root) {
        return isContainerType(variableTypes.get(root));
    }

    // 作用：查询声明类型；逻辑：按变量名返回上下文中的类型，缺失时返回 null。
    public String declaredType(String value) {
        return variableTypes.get(value);
    }

    /**
     * Declared types for parameters, locals, fields and inferred lambda
     * parameters. Call resolution uses the same view as value-flow so a lambda
     * parameter can never be typed in one layer and unknown in the other.
     */
    // 作用：暴露变量类型表；逻辑：返回参数、局部、字段与推断 lambda 参数的类型映射。
    public Map<String, String> variableTypes() {
        return variableTypes;
    }

    // 作用：提取简单类型名；逻辑：去掉泛型与包名前缀。
    public String simpleType(String declaredType) {
        if (declaredType == null) {
            return "";
        }
        String type = declaredType.trim();
        int generic = type.indexOf('<');
        if (generic >= 0) {
            type = type.substring(0, generic);
        }
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }

    // 作用：判断访问器是否有字段证据；逻辑：无接收者类型时沿用名称规则，有类型时要求字段存在且非 JDK 值类型。
    private boolean hasAccessorEvidence(MethodCallExpr call, String property) {
        String receiverType = resolveReceiverType(call.getScope().orElse(null));
        if (receiverType == null || receiverType.isBlank()) {
            // Without a type, preserve the previous name-based fallback. The
            // graph can still be inspected as an unresolved call candidate.
            return true;
        }
        if (typeCatalog != null && typeCatalog.type(receiverType) != null) {
            return typeCatalog.fieldNames(receiverType).stream()
                    .map(AccessorFieldIdentity::canonicalPropertyName)
                    .anyMatch(property::equals);
        }
        // JDK value methods (Date.getTime, BigDecimal.scale, ...) are
        // conversions, not JavaBean field reads.
        return !JavaValueTypes.isValueType(receiverType);
    }

    // 作用：限定访问器字段路径；逻辑：接收者键加属性名，接收者为空时用 this。
    private String qualifyAccessor(MethodCallExpr call, String property) {
        String receiver = call.getScope()
                .map(this::key)
                .filter(value -> !value.isBlank())
                .orElse("this");
        return receiver + "." + property;
    }

    // 作用：判断字段是否存在；逻辑：优先查类型目录，缺少目录时退回变量类型表。
    private boolean hasField(String fieldName) {
        if (declaringTypeQname == null || typeCatalog == null) {
            return variableTypes.containsKey("this." + fieldName)
                    || variableTypes.containsKey(fieldName);
        }
        return typeCatalog.fieldNames(declaringTypeQname).contains(fieldName);
    }

    // 作用：解析接收者类型；逻辑：按 this、变量、字段、创建、强转与方法返回值逐类推导。
    private String resolveReceiverType(Expression receiver) {
        Expression value = unwrap(receiver);
        if (value == null || value instanceof ThisExpr) {
            return declaringTypeQname;
        }
        if (value instanceof NameExpr name) {
            return resolveDeclaredType(variableTypes.get(name.getNameAsString()));
        }
        if (value instanceof FieldAccessExpr field && field.getScope().isThisExpr()) {
            return resolveDeclaredType(variableTypes.get(field.getNameAsString()));
        }
        if (value instanceof FieldAccessExpr field) {
            String owner = resolveReceiverType(field.getScope());
            if (typeCatalog == null || owner == null || typeCatalog.type(owner) == null) {
                return null;
            }
            return resolveDeclaredType(typeCatalog.fieldTypeInHierarchy(owner, field.getNameAsString()));
        }
        if (value instanceof ObjectCreationExpr creation) {
            return resolveDeclaredType(creation.getType().asString());
        }
        if (value instanceof CastExpr cast) {
            return resolveDeclaredType(cast.getType().asString());
        }
        if (value instanceof MethodCallExpr call) {
            String owner = call.getScope().map(this::resolveReceiverType).orElse(declaringTypeQname);
            if (typeCatalog == null || owner == null || typeCatalog.type(owner) == null) {
                return null;
            }
            String returnType = typeCatalog.methodReturnType(
                    owner,
                    call.getNameAsString(),
                    call.getArguments().size()
            );
            if (returnType != null) {
                return resolveDeclaredType(returnType);
            }
            String property = AccessorFieldIdentity.propertyFromAccessor(
                    call.getNameAsString(),
                    true
            );
            if (property == null && call.getArguments().isEmpty()) {
                property = directFieldProperty(call, true);
            }
            return property == null
                    ? null
                    : resolveDeclaredType(
                            typeCatalog.fieldTypeForPropertyInHierarchy(owner, property)
                    );
        }
        return null;
    }

    // 作用：识别同名字段方法；逻辑：参数个数匹配且名称确为接收者字段时返回字段名。
    private String directFieldProperty(MethodCallExpr call, boolean getter) {
        if (call == null || call.getScope().isEmpty()) {
            return null;
        }
        int expectedArguments = getter ? 0 : 1;
        if (call.getArguments().size() != expectedArguments) {
            return null;
        }
        String receiverType = resolveReceiverType(call.getScope().orElseThrow());
        if (receiverType == null || typeCatalog == null || typeCatalog.type(receiverType) == null) {
            return null;
        }
        String name = call.getNameAsString();
        return typeCatalog.fieldNames(receiverType).contains(name) ? name : null;
    }

    // 作用：解析声明类型全名；逻辑：规范化后借助编译单元与类型目录解析，失败时退回规范化名称。
    private String resolveDeclaredType(String declaredType) {
        if (declaredType == null || declaredType.isBlank()) {
            return null;
        }
        String normalized = JavaTypeNameResolver.normalize(declaredType);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        if (typeCatalog == null) {
            return normalized;
        }
        com.github.javaparser.ast.CompilationUnit unit =
                callable.findCompilationUnit().orElse(null);
        if (unit != null) {
            String resolved = new JavaTypeNameResolver(typeCatalog).resolve(unit, normalized);
            if (resolved != null) {
                return resolved;
            }
        }
        String catalogType = typeCatalog.resolveTypeName(simpleType(normalized));
        return catalogType == null ? normalized : catalogType;
    }

    // 作用：规范化 Map 键；逻辑：安全字符串键保留明文，其他字面量用哈希，表达式用通配符。
    private String mapKey(Expression key) {
        Expression value = unwrap(key);
        if (value instanceof com.github.javaparser.ast.expr.StringLiteralExpr literal
                && SAFE_MAP_KEY.matcher(literal.asString()).matches()) {
            return "\"" + literal.asString() + "\"";
        }
        if (value instanceof LiteralExpr) {
            return "#" + Ids.sha256(value.toString()).substring(0, 8);
        }
        return "*";
    }

    // 作用：提取表达式根名；逻辑：逐层剥离调用作用域、字段访问与数组下标，返回变量名。
    private static String rootName(Expression expression) {
        Expression current = unwrap(expression);
        while (current instanceof MethodCallExpr call && call.getScope().isPresent()) {
            current = unwrap(call.getScope().orElseThrow());
        }
        while (current instanceof FieldAccessExpr field) {
            current = unwrap(field.getScope());
        }
        while (current instanceof ArrayAccessExpr access) {
            current = unwrap(access.getName());
        }
        return current instanceof NameExpr name ? name.getNameAsString() : null;
    }

    // 作用：去除表达式包装；逻辑：循环剥离括号与强制类型转换。
    private static Expression unwrap(Expression expression) {
        Expression value = expression;
        while (value instanceof EnclosedExpr enclosed || value instanceof CastExpr) {
            value = value instanceof EnclosedExpr
                    ? ((EnclosedExpr) value).getInner()
                    : ((CastExpr) value).getExpression();
        }
        return value;
    }

    public record StreamChain(Expression source, List<MethodCallExpr> stages) {
    }

    private record LambdaAliasCandidate(String root, String parameter) {
    }
}
