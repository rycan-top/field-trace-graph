package io.fieldtracegraph.javaanalysis.resolution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：推断泛型实参类型。
// 使用场景：解析 List<User>、Optional<T> 等容器元素类型。

/**
 * Resolves member types while preserving generic arguments. JavaParser and the
 * source catalog deliberately keep the declared type expression (for example
 * {@code Wrapper<Order>}) so a member such as {@code T value} can be
 * substituted with {@code Order} instead of being erased to {@code Object}.
 */
public final class TypeArgumentResolver {
    private static final Set<String> COLLECTIONS = Set.of(
            "Collection", "Iterable", "List", "ArrayList", "LinkedList", "Set", "HashSet",
            "LinkedHashSet", "TreeSet", "SortedSet", "NavigableSet", "Queue", "Deque",
            "ArrayDeque", "PriorityQueue", "Stack", "Vector", "CopyOnWriteArrayList", "Stream",
            "IntStream", "LongStream", "DoubleStream"
    );
    private static final Set<String> WRAPPERS = Set.of(
            "Optional", "OptionalInt", "OptionalLong", "OptionalDouble", "CompletableFuture",
            "Future", "CompletionStage", "AtomicReference", "Supplier", "Callable"
    );
    private static final Set<String> METHODS = Set.of(
            "get", "getOrDefault", "poll", "pop", "next", "first", "last",
            "join", "getNow", "apply", "call", "stream", "parallelStream", "iterator",
            "spliterator", "values", "keySet", "entrySet", "findFirst", "findAny",
            "orElse", "orElseGet", "orElseThrow", "filter", "limit", "skip", "sorted",
            "distinct", "peek", "sequential", "parallel", "unordered", "map", "flatMap",
            "toList", "toSet", "toArray", "reduce", "min", "max", "count", "anyMatch",
            "allMatch", "noneMatch", "sum", "average", "boxed"
    );

    private final SourceTypeCatalog catalog;

    // 作用：创建泛型实参解析器；逻辑：保存用于查询类型参数与成员声明的类型目录。
    public TypeArgumentResolver(SourceTypeCatalog catalog) {
        this.catalog = catalog;
    }

    // 作用：解析带泛型属性的字段类型；逻辑：解析宿主类型与类型实参，建立绑定后递归查找字段声明。
    public String fieldType(String ownerTypeExpression, String fieldName) {
        TypeUse owner = TypeUse.parse(ownerTypeExpression);
        String qname = resolveQname(owner.raw());
        if (qname == null) {
            return null;
        }
        return fieldType(
                qname,
                bindings(qname, owner.arguments()),
                fieldName,
                new HashSet<>()
        );
    }

    // 作用：在继承链中查找并替换字段类型；逻辑：先查本类字段表达式，未命中则替换父类型实参后递归查找。
    private String fieldType(
            String qname,
            Map<String, TypeUse> bindings,
            String fieldName,
            Set<String> visiting
    ) {
        if (qname == null || !visiting.add(qname)) {
            return null;
        }
        try {
            String expression = catalog.fieldTypeExpression(qname, fieldName);
            if (expression != null) {
                return substitute(expression, bindings).render();
            }
            for (String superExpression : catalog.superTypeExpressions(qname)) {
                TypeUse superType = substitute(superExpression, bindings);
                String superQname = resolveQname(superType.raw());
                String result = fieldType(
                        superQname,
                        bindings(superQname, superType.arguments()),
                        fieldName,
                        visiting
                );
                if (result != null) {
                    return result;
                }
            }
            return null;
        } finally {
            visiting.remove(qname);
        }
    }

    // 作用：解析方法返回类型；逻辑：无调用实参类型时以空列表转交完整重载。
    public String methodReturnType(String ownerTypeExpression, String methodName, int argumentCount) {
        return methodReturnType(ownerTypeExpression, methodName, argumentCount, List.of());
    }

    // 作用：解析容器与泛型方法的返回类型；逻辑：先处理 Map 视图、流与 Optional 等内置方法，再进入声明方法查找。
    public String methodReturnType(
            String ownerTypeExpression,
            String methodName,
            int argumentCount,
            List<String> argumentTypes
    ) {
        TypeUse owner = TypeUse.parse(ownerTypeExpression);
        String qname = resolveQname(owner.raw());
        if (qname == null) {
            return null;
        }
        if ("values".equals(methodName) || "keySet".equals(methodName)) {
            String mapType = mapType(ownerTypeExpression, methodName);
            if (mapType != null) {
                return mapType;
            }
        }
        if ("entrySet".equals(methodName)) {
            String key = mapKeyType(ownerTypeExpression);
            String value = mapValueType(ownerTypeExpression);
            if (key != null && value != null) {
                return "Set<Map.Entry<" + key + "," + value + ">>";
            }
        }
        String element = elementType(ownerTypeExpression);
        if (element != null && METHODS.contains(methodName)) {
            String ownerSimple = simpleName(owner.raw());
            boolean optionalOwner = Set.of(
                    "Optional", "OptionalInt", "OptionalLong", "OptionalDouble"
            ).contains(ownerSimple);
            boolean streamOwner = ownerSimple.endsWith("Stream");
            return switch (methodName) {
                case "stream", "parallelStream" -> "Stream<" + element + ">";
                case "findFirst", "findAny" -> "Optional<" + element + ">";
                case "iterator" -> "Iterator<" + element + ">";
                case "spliterator" -> "Spliterator<" + element + ">";
                case "filter", "limit", "skip", "sorted", "distinct", "peek",
                        "sequential", "parallel", "unordered", "boxed" ->
                        streamOwner ? "Stream<" + element + ">" : "Optional<" + element + ">";
                case "map", "flatMap" -> optionalOwner
                        ? "Optional<Object>"
                        : streamOwner ? "Stream<Object>" : "Object";
                case "toList" -> "List<" + element + ">";
                case "toSet" -> "Set<" + element + ">";
                case "toArray" -> "Object[]";
                case "reduce" -> argumentCount == 1
                        ? "Optional<" + element + ">"
                        : element;
                case "min", "max" -> "Optional<" + element + ">";
                case "count" -> "long";
                case "anyMatch", "allMatch", "noneMatch" -> "boolean";
                case "sum" -> "double";
                case "average" -> "OptionalDouble";
                case "orElse", "orElseGet", "orElseThrow" -> element;
                default -> element;
            };
        }
        return methodReturnType(
                qname,
                bindings(qname, owner.arguments()),
                methodName,
                argumentCount,
                argumentTypes,
                new HashSet<>()
        );
    }

    // 作用：在类型继承链中解析方法返回类型；逻辑：选取匹配方法并替换方法泛型绑定，未命中时递归检查父类型。
    private String methodReturnType(
            String qname,
            Map<String, TypeUse> bindings,
            String methodName,
            int argumentCount,
            List<String> argumentTypes,
            Set<String> visiting
    ) {
        if (qname == null || !visiting.add(qname)) {
            return null;
        }
        try {
            List<SourceTypeCatalog.MethodInfo> methods =
                    catalog.declaredMethods(qname, methodName, argumentCount);
            SourceTypeCatalog.MethodInfo selected = selectMethod(methods, argumentTypes, bindings);
            if (selected == null) {
                for (SourceTypeCatalog.MethodInfo method : methods) {
                    if (method.returnTypeExpression() != null) {
                        return substitute(
                                method.returnTypeExpression(),
                                methodBindings(bindings, method, List.of())
                        ).render();
                    }
                }
            } else {
                if (selected.returnTypeExpression() == null) {
                    return null;
                }
                return substitute(
                        selected.returnTypeExpression(),
                        methodBindings(bindings, selected, argumentTypes)
                ).render();
            }
            for (String superExpression : catalog.superTypeExpressions(qname)) {
                TypeUse superType = substitute(superExpression, bindings);
                String superQname = resolveQname(superType.raw());
                String result = methodReturnType(
                        superQname,
                        bindings(superQname, superType.arguments()),
                        methodName,
                        argumentCount,
                        argumentTypes,
                        visiting
                );
                if (result != null) {
                    return result;
                }
            }
            return null;
        } finally {
            visiting.remove(qname);
        }
    }

    // 作用：从同名重载中选择最匹配的方法；逻辑：唯一候选直接返回，否则按实参类型推断绑定并筛选。
    private SourceTypeCatalog.MethodInfo selectMethod(
            List<SourceTypeCatalog.MethodInfo> methods,
            List<String> argumentTypes,
            Map<String, TypeUse> bindings
    ) {
        if (methods.isEmpty()) {
            return null;
        }
        if (methods.size() == 1 || argumentTypes == null || argumentTypes.isEmpty()
                || argumentTypes.size() != methods.get(0).parameterTypeExpressions().size()) {
            return methods.get(0);
        }
        List<SourceTypeCatalog.MethodInfo> matching = new ArrayList<>();
        for (SourceTypeCatalog.MethodInfo method : methods) {
            if (method.parameterTypeExpressions().size() != argumentTypes.size()) {
                continue;
            }
            Map<String, TypeUse> inferred = inferMethodBindings(method, argumentTypes, bindings);
            if (inferred != null) {
                matching.add(method);
            }
        }
        return matching.size() == 1 ? matching.get(0) : matching.isEmpty() ? methods.get(0) : matching.get(0);
    }

    // 作用：合并类级与方法级泛型绑定；逻辑：优先使用实参推断结果，并为未推断出的方法类型参数补 Object。
    private Map<String, TypeUse> methodBindings(
            Map<String, TypeUse> classBindings,
            SourceTypeCatalog.MethodInfo method,
            List<String> argumentTypes
    ) {
        Map<String, TypeUse> inferred = argumentTypes == null || argumentTypes.isEmpty()
                ? null
                : inferMethodBindings(method, argumentTypes, classBindings);
        Map<String, TypeUse> result = inferred == null
                ? new LinkedHashMap<>(classBindings)
                : new LinkedHashMap<>(inferred);
        for (String parameter : method.typeParameters()) {
            result.putIfAbsent(parameter, new TypeUse("Object", List.of(), 0));
        }
        return result;
    }

    // 作用：由调用实参推断方法泛型；逻辑：逐参数与形参类型统一，任一参数不兼容则放弃该方法。
    private Map<String, TypeUse> inferMethodBindings(
            SourceTypeCatalog.MethodInfo method,
            List<String> argumentTypes,
            Map<String, TypeUse> classBindings
    ) {
        if (method.parameterTypeExpressions().size() != argumentTypes.size()) {
            return null;
        }
        Map<String, TypeUse> result = new LinkedHashMap<>(classBindings);
        for (int index = 0; index < argumentTypes.size(); index++) {
            String actual = argumentTypes.get(index);
            if (actual == null || actual.isBlank()) {
                continue;
            }
            TypeUse formalType = TypeUse.parse(method.parameterTypeExpressions().get(index));
            if (!unify(formalType, TypeUse.parse(actual), result, method.typeParameters())) {
                return null;
            }
        }
        return result;
    }

    // 作用：统一形参与实参类型；逻辑：类型变量直接绑定，Object 与通配符放宽，同原始类型继续递归比较泛型实参。
    private boolean unify(
            TypeUse formal,
            TypeUse actual,
            Map<String, TypeUse> bindings,
            List<String> methodTypeParameters
    ) {
        if (methodTypeParameters.contains(formal.raw()) && formal.arguments().isEmpty()) {
            bindings.putIfAbsent(formal.raw(), actual);
            return true;
        }
        if ("Object".equals(simpleName(formal.raw())) || "?".equals(formal.raw())) {
            return true;
        }
        if (formal.raw().isBlank() || actual.raw().isBlank()) {
            return true;
        }
        if (!sameRawType(formal.raw(), actual.raw())) {
            return isAssignable(actual.raw(), formal.raw());
        }
        if (formal.arguments().size() != actual.arguments().size()) {
            return true;
        }
        for (int index = 0; index < formal.arguments().size(); index++) {
            if (!unify(formal.arguments().get(index), actual.arguments().get(index),
                    bindings, methodTypeParameters)) {
                return false;
            }
        }
        return true;
    }

    // 作用：比较两个类型的原始类型；逻辑：取简单名后判断是否相等。
    private boolean sameRawType(String first, String second) {
        return simpleName(first).equals(simpleName(second));
    }

    // 作用：判断实参类型能否赋给形参类型；逻辑：解析双方全名后委托类型目录的继承关系判断。
    private boolean isAssignable(String actual, String formal) {
        String actualQname = resolveQname(actual);
        String formalQname = resolveQname(formal);
        return actualQname != null && formalQname != null
                && catalog.isSubtype(actualQname, formalQname);
    }

    // 作用：解析容器元素类型；逻辑：数组降一维，集合与包装器取首个实参，Map 取值类型。
    public String elementType(String containerTypeExpression) {
        TypeUse container = TypeUse.parse(containerTypeExpression);
        if (container.arrayDepth() > 0) {
            return new TypeUse(container.raw(), container.arguments(), container.arrayDepth() - 1).render();
        }
        String simple = simpleName(container.raw());
        if (COLLECTIONS.contains(simple) || WRAPPERS.contains(simple)) {
            return container.arguments().isEmpty()
                    ? null
                    : container.arguments().get(0).render();
        }
        if ("Map".equals(simple) || simple.endsWith("Map")) {
            return container.arguments().size() < 2
                    ? null
                    : container.arguments().get(1).render();
        }
        return null;
    }

    // 作用：解析 Map 键类型；逻辑：取第一个泛型实参，缺失时返回空。
    public String mapKeyType(String mapTypeExpression) {
        TypeUse map = TypeUse.parse(mapTypeExpression);
        return map.arguments().isEmpty() ? null : map.arguments().get(0).render();
    }

    // 作用：解析 Map 值类型；逻辑：复用容器元素类型解析。
    public String mapValueType(String mapTypeExpression) {
        return elementType(mapTypeExpression);
    }

    // 作用：推导 Map 视图方法的返回类型；逻辑：keySet 返回键集合，values 返回值集合，其他返回空。
    private String mapType(String ownerTypeExpression, String methodName) {
        TypeUse owner = TypeUse.parse(ownerTypeExpression);
        String simple = simpleName(owner.raw());
        if (!"Map".equals(simple) && !simple.endsWith("Map")) {
            return null;
        }
        if ("keySet".equals(methodName)) {
            String key = mapKeyType(ownerTypeExpression);
            return key == null ? null : "Set<" + key + ">";
        }
        if ("values".equals(methodName)) {
            String value = mapValueType(ownerTypeExpression);
            return value == null ? null : "Collection<" + value + ">";
        }
        return null;
    }

    // 作用：建立类型参数到实参的绑定；逻辑：按声明顺序把类型目录中的类型参数与调用方实参一一对应。
    private Map<String, TypeUse> bindings(String qname, List<TypeUse> arguments) {
        Map<String, TypeUse> result = new LinkedHashMap<>();
        List<String> parameters = catalog.typeParameters(qname);
        for (int index = 0; index < parameters.size() && index < arguments.size(); index++) {
            result.put(parameters.get(index), arguments.get(index));
        }
        return result;
    }

    // 作用：替换类型表达式中的泛型变量；逻辑：先解析表达式再转交结构化替换。
    private TypeUse substitute(String expression, Map<String, TypeUse> bindings) {
        return substitute(TypeUse.parse(expression), bindings);
    }

    // 作用：递归替换类型中的泛型变量；逻辑：命中绑定且无实参时整体替换，否则递归替换各泛型实参。
    private TypeUse substitute(TypeUse type, Map<String, TypeUse> bindings) {
        TypeUse bound = bindings.get(type.raw());
        if (bound != null && type.arguments().isEmpty()) {
            return type.arrayDepth() == 0
                    ? bound
                    : new TypeUse(bound.raw(), bound.arguments(), bound.arrayDepth() + type.arrayDepth());
        }
        if (type.arguments().isEmpty()) {
            return type;
        }
        List<TypeUse> arguments = new ArrayList<>();
        for (TypeUse argument : type.arguments()) {
            arguments.add(substitute(argument, bindings));
        }
        return new TypeUse(type.raw(), List.copyOf(arguments), type.arrayDepth());
    }

    // 作用：把原始类型名解析为目录全名；逻辑：已含点号直接使用，否则查全局索引，仍未命中时保留原名。
    private String resolveQname(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = JavaTypeNameResolver.normalize(raw);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        if (normalized.contains(".")) {
            return normalized;
        }
        String resolved = catalog.resolveTypeName(normalized);
        return resolved == null ? normalized : resolved;
    }

    // 作用：提取类型简单名；逻辑：去掉泛型参数与包名前缀后返回末段名称。
    private String simpleName(String value) {
        int generic = value.indexOf('<');
        String type = generic < 0 ? value : value.substring(0, generic);
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }

    private record TypeUse(String raw, List<TypeUse> arguments, int arrayDepth) {
        // 作用：解析类型表达式；逻辑：处理通配符、数组与可变参数，再递归拆分泛型实参。
        private static TypeUse parse(String expression) {
            if (expression == null || expression.isBlank()) {
                return new TypeUse("", List.of(), 0);
            }
            String value = expression.trim();
            if (value.startsWith("? extends ")) {
                value = value.substring("? extends ".length()).trim();
            } else if (value.startsWith("? super ")) {
                return new TypeUse("Object", List.of(), 0);
            }
            int arrayDepth = 0;
            while (value.endsWith("[]")) {
                arrayDepth++;
                value = value.substring(0, value.length() - 2).trim();
            }
            if (value.endsWith("...")) {
                arrayDepth++;
                value = value.substring(0, value.length() - 3).trim();
            }
            int open = value.indexOf('<');
            if (open < 0) {
                return new TypeUse(value, List.of(), arrayDepth);
            }
            int close = matchingGeneric(value, open);
            if (close < 0) {
                return new TypeUse(value, List.of(), arrayDepth);
            }
            String raw = value.substring(0, open).trim();
            List<TypeUse> arguments = new ArrayList<>();
            for (String argument : splitArguments(value.substring(open + 1, close))) {
                arguments.add(parse(argument));
            }
            return new TypeUse(raw, List.copyOf(arguments), arrayDepth);
        }

        // 作用：还原类型表达式文本；逻辑：拼接原始类型、泛型实参与数组维度。
        private String render() {
            StringBuilder result = new StringBuilder(raw);
            if (!arguments.isEmpty()) {
                result.append('<');
                for (int index = 0; index < arguments.size(); index++) {
                    if (index > 0) {
                        result.append(',');
                    }
                    result.append(arguments.get(index).render());
                }
                result.append('>');
            }
            result.append("[]".repeat(arrayDepth));
            return result.toString();
        }

        // 作用：寻找泛型参数闭合位置；逻辑：统计尖括号深度，深度归零时的位置即为闭合符。
        private static int matchingGeneric(String value, int open) {
            int depth = 0;
            for (int index = open; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current == '<') {
                    depth++;
                } else if (current == '>') {
                    depth--;
                    if (depth == 0) {
                        return index;
                    }
                }
            }
            return -1;
        }

        // 作用：拆分泛型实参列表；逻辑：按顶层逗号分割，嵌套尖括号内的逗号不切分。
        private static List<String> splitArguments(String value) {
            List<String> result = new ArrayList<>();
            int depth = 0;
            int start = 0;
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current == '<') {
                    depth++;
                } else if (current == '>') {
                    depth--;
                } else if (current == ',' && depth == 0) {
                    result.add(value.substring(start, index).trim());
                    start = index + 1;
                }
            }
            result.add(value.substring(start).trim());
            return result;
        }
    }
}
