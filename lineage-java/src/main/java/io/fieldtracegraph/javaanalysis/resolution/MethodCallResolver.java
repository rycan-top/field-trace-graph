package io.fieldtracegraph.javaanalysis.resolution;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.project.ClasspathTypeIndex;
import io.fieldtracegraph.javaanalysis.semantic.protocol.CallAttribution;

import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：解析方法调用并处理重载消歧。
// 使用场景：生成调用边、确定被调方法以及标记歧义候选。

public final class MethodCallResolver {
    private static final Set<String> OBJECT_METHODS = Set.of(
            "clone", "equals", "finalize", "getClass", "hashCode",
            "notify", "notifyAll", "toString", "wait"
    );

    private final SourceTypeCatalog catalog;
    private final Map<String, CallAttribution> attributions;
    private final JavaTypeNameResolver typeNameResolver;
    private final ReceiverTypeResolver receiverTypeResolver;
    private final ClasspathTypeIndex classpathTypes;

    // 作用：创建带外部类型索引的解析器；逻辑：保存目录、语义归属、外部类型索引，并初始化类型名与接收者解析器。
    public MethodCallResolver(
            SourceTypeCatalog catalog,
            Map<String, CallAttribution> attributions,
            ClasspathTypeIndex classpathTypes
    ) {
        this.catalog = catalog;
        this.attributions = attributions;
        this.classpathTypes = classpathTypes == null ? ClasspathTypeIndex.empty() : classpathTypes;
        this.typeNameResolver = new JavaTypeNameResolver(catalog);
        this.receiverTypeResolver = new ReceiverTypeResolver(catalog);
    }

    // 作用：解析一次方法调用；逻辑：优先使用 javac 归属，再按接收者判定虚调用或静态调用，无法定位时给出边界原因。
    public CallResolution resolveMethodCall(
            SourceFileRecord source,
            CompilationUnit unit,
            CallableScope scope,
            Map<String, String> localTypes,
            MethodCallExpr call
    ) {
        String methodName = call.getNameAsString();
        int argumentCount = call.getArguments().size();
        String fallbackQname = fallbackQname(call, methodName, argumentCount);
        int line = call.getBegin().map(position -> position.line).orElse(1);
        int column = call.getBegin().map(position -> position.column).orElse(1);
        CallResolution attributed = resolveAttributed(
                source, line, column, methodName, argumentCount, fallbackQname
        );
        if (attributed != null) {
            return attributed;
        }
        Expression receiver = call.getScope().orElse(null);
        if (receiver == null || receiver instanceof ThisExpr) {
            return resolveVirtual(unit, scope, localTypes, scope.typeQname(), methodName,
                    call.getArguments(), fallbackQname);
        }
        String receiverType = receiverTypeResolver.resolve(unit, scope, localTypes, receiver);
        if (receiverType == null) {
            return CallResolution.unresolved(
                    fallbackQname,
                    unknownReceiverReason(unit, scope, localTypes, receiver, methodName)
            );
        }
        if (receiverTypeResolver.isInstanceReceiver(unit, scope, localTypes, receiver)) {
            return resolveVirtual(unit, scope, localTypes, receiverType, methodName,
                    call.getArguments(), fallbackQname);
        }
        return resolveStatic(unit, scope, localTypes, receiverType, methodName,
                call.getArguments(), fallbackQname);
    }

    // 作用：构造无法解析时的兜底目标名；逻辑：拼接截断后的接收者、方法名与实参数量。
    private String fallbackQname(MethodCallExpr call, String methodName, int argumentCount) {
        String receiver = call.getScope()
                .map(Object::toString)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .orElse("");
        if (receiver.length() > 120) {
            receiver = receiver.substring(0, 120) + "...";
        }
        String target = receiver.isBlank() ? methodName : receiver + "#" + methodName;
        return target + "(" + argumentCount + ")";
    }

    /**
     * Classifies how a call is dispatched. Field receivers and parameters are
     * instance calls even though they are written as bare names, so the static
     * shortcut only applies to names that resolve to a type.
     */
    // 作用：判断调用的分派方式；逻辑：无接收者或接收者解析为实例值时是 VIRTUAL，否则是 STATIC。
    public String dispatchKind(
            CompilationUnit unit,
            CallableScope scope,
            Map<String, String> localTypes,
            MethodCallExpr call
    ) {
        Expression receiver = call.getScope().orElse(null);
        if (receiver == null) {
            return "VIRTUAL";
        }
        return receiverTypeResolver.isInstanceReceiver(unit, scope, localTypes, receiver)
                ? "VIRTUAL"
                : "STATIC";
    }

    // 作用：解析构造调用；逻辑：优先使用归属结果，否则按类型名查构造器，并区分生成代码缺失与外部类型。
    public CallResolution resolveConstructor(SourceFileRecord source, CompilationUnit unit, ObjectCreationExpr creation) {
        String typeName = creation.getType().asString();
        int argumentCount = creation.getArguments().size();
        String fallbackQname = typeName + "#<init>(" + argumentCount + ")";
        int line = creation.getBegin().map(position -> position.line).orElse(1);
        int column = creation.getBegin().map(position -> position.column).orElse(1);
        CallResolution attributed = resolveAttributed(
                source, line, column, "<init>", argumentCount, fallbackQname
        );
        if (attributed != null) {
            return attributed;
        }
        String typeQname = typeNameResolver.resolve(unit, typeName);
        if (typeQname == null) {
            return CallResolution.unresolved(
                    fallbackQname,
                    unknownReceiverReason(unit, null, Map.of(), creation, "<init>")
            );
        }
        List<SourceTypeCatalog.MethodInfo> candidates = catalog.findConstructors(typeQname, argumentCount);
        if (candidates.isEmpty() && catalog.hasGeneratedConstructor(typeQname, argumentCount)) {
            return CallResolution.unresolved(fallbackQname, "GENERATED_CODE_MISSING");
        }
        return decide(candidates, fallbackQname, typeQname, "<init>");
    }

    // 作用：解析 this(...) 或 super(...) 构造调用；逻辑：确定目标构造类型，查候选构造器，外部类型则给出边界原因。
    public CallResolution resolveExplicitConstructorInvocation(
            SourceFileRecord source,
            CompilationUnit unit,
            CallableScope scope,
            ExplicitConstructorInvocationStmt invocation
    ) {
        int argumentCount = invocation.getArguments().size();
        String superTypeExpression = invocation.isThis()
                ? null
                : firstSuperTypeExpression(scope.typeQname());
        String typeQname = invocation.isThis()
                ? scope.typeQname()
                : firstSuperType(scope.typeQname());
        if (typeQname == null) {
            return CallResolution.unresolved(
                    scope.typeQname() + "#<init>(" + argumentCount + ")",
                    superTypeExpression == null
                            ? "EXTERNAL"
                            : externalTypeReason(unit, superTypeExpression, "<init>")
            );
        }
        String fallbackQname = typeQname + "#<init>(" + argumentCount + ")";
        List<SourceTypeCatalog.MethodInfo> candidates = catalog.findConstructors(typeQname, argumentCount);
        if (candidates.isEmpty() && catalog.type(typeQname) == null) {
            return CallResolution.unresolved(
                    fallbackQname,
                    externalTypeReason(
                            unit,
                            superTypeExpression == null ? typeQname : superTypeExpression,
                            "<init>"
                    )
            );
        }
        return decide(candidates, fallbackQname, typeQname, "<init>");
    }

    // 作用：按 javac 归属解析调用；逻辑：先查精确位置键再查旧格式键，内部类型继续按签名筛选，外部类型转入边界分类。
    private CallResolution resolveAttributed(
            SourceFileRecord source,
            int line,
            int column,
            String methodName,
            int argumentCount,
            String fallbackQname
    ) {
        if (source == null) {
            return null;
        }
        CallAttribution attribution = attributions.get(
                source.relativePath() + ":" + line + ":" + column + ":" + methodName + ":" + argumentCount
        );
        if (attribution == null) {
            attribution = uniqueLegacyAttribution(source, line, methodName, argumentCount);
        }
        if (attribution == null) {
            return null;
        }
        if (catalog.type(attribution.declaringTypeQname()) == null) {
            return externalResolution(attribution, methodName, argumentCount);
        }
        if ("<init>".equals(methodName)) {
            List<SourceTypeCatalog.MethodInfo> exact = exactMethods(
                    catalog.findConstructors(attribution.declaringTypeQname(), -1),
                    attribution
            );
            if (!exact.isEmpty()) {
                return decide(exact, fallbackQname, attribution.declaringTypeQname(), "<init>");
            }
            return decide(
                    catalog.findConstructors(attribution.declaringTypeQname(), argumentCount),
                    fallbackQname,
                    attribution.declaringTypeQname(),
                    "<init>"
            );
        }
        List<SourceTypeCatalog.MethodInfo> exact = exactMethods(
                catalog.findMethods(attribution.declaringTypeQname(), methodName, -1),
                attribution
        );
        if (!exact.isEmpty()) {
            return decideExactVirtual(attribution.declaringTypeQname(), methodName, argumentCount, exact, fallbackQname);
        }
        return decideVirtual(
                attribution.declaringTypeQname(),
                methodName,
                argumentCount,
                fallbackQname
        );
    }

    // 作用：把外部归属转换为未解析边界；逻辑：按 RPC、平台库、已索引外部库与缺失 classpath 的顺序归类原因。
    private CallResolution externalResolution(
            CallAttribution attribution,
            String methodName,
            int argumentCount
    ) {
        String owner = attribution.declaringTypeQname();
        String target = owner + "#" + methodName + "("
                + String.join(",", attribution.parameterTypes() == null
                ? List.of()
                : attribution.parameterTypes())
                + ")";
        /*
         * The declaring interface may live in a dependency JAR while its
         * implementation is analysed source. Follow the source implementation
         * before declaring an external boundary so RPC/service argument and
         * return field bridges still connect.
         */
        List<SourceTypeCatalog.MethodInfo> sourceImplementations =
                catalog.findConcreteOverrides(
                        owner,
                        methodName,
                        argumentCount,
                        attribution.parameterTypes() == null
                                ? List.of()
                                : attribution.parameterTypes()
                );
        if (sourceImplementations.size() == 1) {
            SourceTypeCatalog.MethodInfo implementation = sourceImplementations.get(0);
            return CallResolution.virtualUnique(implementation.symbolId(), implementation.qname());
        }
        if (sourceImplementations.size() > 1) {
            return CallResolution.virtualAmbiguous(sourceImplementations.stream()
                    .map(implementation -> new CallResolution.Target(
                            implementation.symbolId(), implementation.qname()))
                    .toList());
        }
        String reason;
        String rpcBoundary = rpcReason(owner, methodName);
        if (rpcBoundary != null) {
            reason = rpcBoundary;
        } else if (ClasspathTypeIndex.isPlatformType(owner)) {
            reason = "PLATFORM_LIBRARY";
        } else if (classpathTypes.hasType(owner)
                || classpathTypes.hasMethod(owner, methodName, argumentCount)) {
            reason = "EXTERNAL_LIBRARY";
        } else {
            reason = "CLASSPATH_MISSING";
        }
        return CallResolution.unresolved(target, reason);
    }

    // 作用：判断调用是否属于 RPC 或序列化边界；逻辑：检查开源 RPC/WS 框架的注解与包前缀，再匹配常见序列化方法名。
    private String rpcReason(String owner, String methodName) {
        if (classpathTypes.hasAnnotation(owner, "FeignClient")
                || classpathTypes.hasAnnotation(owner, "DubboReference")
                || classpathTypes.hasAnnotation(owner, "GrpcService")
                || classpathTypes.hasAnnotation(owner, "WebService")
                || classpathTypes.hasAnnotation(owner, "WebServiceClient")) {
            return "RPC_BOUNDARY";
        }
        if (owner != null && (owner.startsWith("org.apache.dubbo.")
                || owner.startsWith("org.apache.thrift.")
                || owner.startsWith("io.grpc.")
                || owner.startsWith("org.apache.cxf.")
                || owner.startsWith("org.jboss.resteasy.")
                || owner.startsWith("org.springframework.cloud.openfeign.")
                || owner.startsWith("javax.jws.")
                || owner.startsWith("jakarta.jws.")
                || owner.startsWith("javax.xml.ws.")
                || owner.startsWith("jakarta.xml.ws."))) {
            return "RPC_BOUNDARY";
        }
        if (Set.of(
                "convertValue",
                "readValue",
                "writeValueAsString",
                "fromJson",
                "toJson",
                "parseObject",
                "parseArray",
                "toJSONString"
        ).contains(methodName)) {
            return "SERIALIZATION_BOUNDARY";
        }
        return null;
    }

    /**
     * Classifies a receiver whose declared type is outside the source catalog.
     * Imports name the intended type even without a classpath, which is enough
     * to separate platform calls, serialization boundaries, confirmed library
     * members and genuinely unknown receiver expressions.
     */
    // 作用：解释接收者类型无法解析的原因；逻辑：识别缺失的同类方法、外部父类字段，并回退到导入候选分类。
    private String unknownReceiverReason(
            CompilationUnit unit,
            CallableScope scope,
            Map<String, String> localTypes,
            Expression receiver,
            String methodName
    ) {
        if (receiver instanceof MethodCallExpr call
                && call.getScope().isEmpty()
                && scope != null
                && catalog.type(scope.typeQname()) != null) {
            return missingCalleeReason(scope.typeQname(), call.getNameAsString());
        }
        if (receiver instanceof NameExpr name
                && scope != null
                && !name.getNameAsString().isEmpty()
                && Character.isLowerCase(name.getNameAsString().charAt(0))
                && !isLambdaParameter(name)
                && !localTypes.containsKey(name.getNameAsString())
                && catalog.fieldTypeExpressionInHierarchy(
                        scope.typeQname(), name.getNameAsString()) == null
                && catalog.hasExternalSupertype(scope.typeQname())) {
            return "CLASSPATH_MISSING";
        }
        return classifyExternalCandidates(
                externalTypeCandidates(unit, scope, localTypes, receiver),
                methodName
        );
    }

    // 作用：判断名称是否为 lambda 参数；逻辑：查找所属 lambda 并比对参数名。
    private boolean isLambdaParameter(NameExpr name) {
        return name.findAncestor(com.github.javaparser.ast.expr.LambdaExpr.class)
                .map(lambda -> lambda.getParameters().stream()
                        .anyMatch(parameter -> parameter.getNameAsString()
                                .equals(name.getNameAsString())))
                .orElse(false);
    }

    // 作用：解释外部类型调用的边界原因；逻辑：先求类型候选，再交由统一候选分类器判定。
    private String externalTypeReason(
            CompilationUnit unit,
            String typeName,
            String methodName
    ) {
        return classifyExternalCandidates(
                typeNameResolver.externalCandidates(unit, typeName),
                methodName
        );
    }

    // 作用：归类外部类型候选；逻辑：依次判断序列化/RPC、平台类型、已索引库与 classpath 缺失。
    private String classifyExternalCandidates(List<String> candidates, String methodName) {
        String methodOnly = rpcReason(null, methodName);
        if (methodOnly != null) {
            return methodOnly;
        }
        for (String candidate : candidates) {
            String rpc = rpcReason(candidate, methodName);
            if (rpc != null) {
                return rpc;
            }
        }
        for (String candidate : candidates) {
            if (ClasspathTypeIndex.isPlatformType(candidate)) {
                return "PLATFORM_LIBRARY";
            }
        }
        for (String candidate : candidates) {
            if (classpathTypes.hasType(candidate)
                    || classpathTypes.hasMethod(candidate, methodName, -1)) {
                return "EXTERNAL_LIBRARY";
            }
        }
        if (!candidates.isEmpty()) {
            return "CLASSPATH_MISSING";
        }
        return "RECEIVER_TYPE_UNKNOWN";
    }

    /**
     * Walks a receiver expression to the type name that introduces it. A chain
     * such as {@code Arrays.stream(x).map(...)} is rooted at {@code Arrays},
     * so the platform classification still applies to the chain.
     */
    // 作用：回溯接收者表达式的外层类型候选；逻辑：按名称、链式调用、字段访问、构造与强转递归提取类型名。
    private List<String> externalTypeCandidates(
            CompilationUnit unit,
            CallableScope scope,
            Map<String, String> localTypes,
            Expression receiver
    ) {
        if (receiver == null || unit == null) {
            return List.of();
        }
        if (receiver instanceof NameExpr name) {
            String value = name.getNameAsString();
            String declared = localTypes.get(value);
            if (declared == null && scope != null) {
                declared = catalog.fieldTypeExpressionInHierarchy(scope.typeQname(), value);
            }
            if (declared != null && !declared.isBlank()) {
                return typeNameResolver.externalCandidates(unit, declared);
            }
            if (value.isEmpty() || !Character.isUpperCase(value.charAt(0))) {
                return List.of();
            }
            return typeNameResolver.externalCandidates(unit, value);
        }
        if (receiver instanceof MethodCallExpr call) {
            return call.getScope()
                    .map(inner -> externalTypeCandidates(unit, scope, localTypes, inner))
                    .orElse(List.of());
        }
        if (receiver instanceof FieldAccessExpr field) {
            String qualifiedType = qualifiedTypeName(field);
            if (qualifiedType != null) {
                return typeNameResolver.externalCandidates(unit, qualifiedType);
            }
            return externalTypeCandidates(unit, scope, localTypes, field.getScope());
        }
        if (receiver instanceof ObjectCreationExpr creation) {
            return typeNameResolver.externalCandidates(unit, creation.getType().asString());
        }
        if (receiver instanceof CastExpr cast) {
            return typeNameResolver.externalCandidates(unit, cast.getType().asString());
        }
        if (receiver instanceof EnclosedExpr enclosed) {
            return externalTypeCandidates(unit, scope, localTypes, enclosed.getInner());
        }
        return List.of();
    }

    private static final Set<String> PACKAGE_ROOTS = Set.of(
            "cn", "com", "dev", "edu", "gov", "io", "java", "javax",
            "jdk", "me", "net", "org", "sun"
    );

    /**
     * A written-out qualified type such as {@code com.alibaba.fastjson.JSON}
     * must not be mistaken for a value chain. Require a package-like root and
     * a capitalized final segment so {@code this.service.port} and
     * {@code EnumType.CONSTANT} still recurse instead.
     */
    // 作用：识别写全的限定类型名；逻辑：要求包根合法、至少三段且末段大写，避免把普通字段链误判为类型。
    private String qualifiedTypeName(FieldAccessExpr field) {
        String text = field.toString();
        String[] segments = text.split("\\.", -1);
        if (segments.length < 3 || !PACKAGE_ROOTS.contains(segments[0])) {
            return null;
        }
        if (segments[segments.length - 1].isEmpty()
                || !Character.isUpperCase(segments[segments.length - 1].charAt(0))) {
            return null;
        }
        for (String segment : segments) {
            if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))) {
                return null;
            }
            for (int index = 1; index < segment.length(); index++) {
                if (!Character.isJavaIdentifierPart(segment.charAt(index))) {
                    return null;
                }
            }
        }
        return text;
    }

    // 作用：按旧格式键查找唯一归属；逻辑：扫描同文件同行同方法的记录，出现多个不同键时放弃。
    private CallAttribution uniqueLegacyAttribution(
            SourceFileRecord source,
            int line,
            String methodName,
            int argumentCount
    ) {
        CallAttribution found = null;
        for (CallAttribution attribution : attributions.values()) {
            if (!source.relativePath().equals(attribution.relativePath())
                    || attribution.line() != line
                    || !methodName.equals(attribution.methodName())
                    || attribution.argumentCount() != argumentCount
                    || !attribution.legacyKey().equals(
                    source.relativePath() + ":" + line + ":" + methodName + ":" + argumentCount
            )) {
                continue;
            }
            if (found != null && !found.key().equals(attribution.key())) {
                return null;
            }
            found = attribution;
        }
        return found;
    }

    // 作用：按 javac 参数类型筛选精确方法；逻辑：逐候选比对形参、实参类型、可变参数标记与实参数量。
    private List<SourceTypeCatalog.MethodInfo> exactMethods(
            List<SourceTypeCatalog.MethodInfo> candidates,
            CallAttribution attribution
    ) {
        if (candidates.isEmpty() || attribution.parameterTypes() == null
                || attribution.parameterTypes().isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(method -> signatureMatches(
                        method.parameterTypes(),
                        attribution.parameterTypes(),
                        attribution.varargs(),
                        attribution.argumentCount()
                ))
                .toList();
    }

    // 作用：在精确候选中决定虚调用目标；逻辑：唯一且非多态时直接编译期确定，否则尝试唯一具体实现。
    private CallResolution decideExactVirtual(
            String typeQname,
            String methodName,
            int argumentCount,
            List<SourceTypeCatalog.MethodInfo> exact,
            String fallbackQname
    ) {
        List<SourceTypeCatalog.MethodInfo> reduced = mostSpecific(exact);
        if (reduced.size() == 1) {
            SourceTypeCatalog.MethodInfo method = reduced.get(0);
            if (method.hasBody() || !catalog.isPolymorphic(typeQname)) {
                return CallResolution.compileTimeTarget(method.symbolId(), method.qname());
            }
            CallResolution implementationResolution = resolveConcreteImplementations(
                    typeQname, methodName, argumentCount, reduced
            );
            if (implementationResolution != null) {
                return implementationResolution;
            }
            return decide(reduced, fallbackQname, typeQname, methodName);
        }
        if (reduced.stream().anyMatch(SourceTypeCatalog.MethodInfo::hasBody)) {
            return decide(reduced, fallbackQname, typeQname, methodName);
        }
        if (catalog.isPolymorphic(typeQname)) {
            CallResolution implementationResolution = resolveConcreteImplementations(
                    typeQname, methodName, argumentCount, reduced
            );
            if (implementationResolution != null) {
                return implementationResolution;
            }
        }
        return decide(reduced, fallbackQname, typeQname, methodName);
    }

    /**
     * Resolves the method bodies a virtual call can reach. A single concrete
     * override is an exact virtual target; several overrides remain ambiguous
     * but are still returned so the call site can bridge arguments and return
     * values into every possible implementation body.
     */
    // 作用：解析抽象声明的具体实现；逻辑：按擦除后的声明签名筛选子类型实现，唯一实现升级为虚调用，多实现保留候选。
    private CallResolution resolveConcreteImplementations(
            String typeQname,
            String methodName,
            int argumentCount,
            List<SourceTypeCatalog.MethodInfo> declarations
    ) {
        List<String> declaredTypes = declarations.isEmpty()
                ? List.of()
                : declarations.get(0).parameterTypes();
        List<SourceTypeCatalog.MethodInfo> implementations =
                catalog.findConcreteOverrides(
                        typeQname, methodName, argumentCount, declaredTypes
                );
        if (implementations.isEmpty()) {
            return null;
        }
        if (implementations.size() == 1) {
            SourceTypeCatalog.MethodInfo implementation = implementations.get(0);
            return CallResolution.virtualUnique(
                    implementation.symbolId(),
                    implementation.qname()
            );
        }
        List<CallResolution.Target> targets = implementations.stream()
                .map(implementation -> new CallResolution.Target(
                        implementation.symbolId(),
                        implementation.qname()
                ))
                .toList();
        return CallResolution.virtualAmbiguous(targets);
    }

    // 作用：比较声明签名与实际调用签名；逻辑：区分普通参数与可变参数，逐项校验类型和数量。
    private boolean signatureMatches(
            List<String> declaredTypes,
            List<String> actualTypes,
            boolean varargs,
            int actualArgumentCount
    ) {
        if (declaredTypes.isEmpty() || actualTypes.isEmpty()) {
            return false;
        }
        boolean declaredVarargs = isVarargsParameter(declaredTypes);
        if (declaredVarargs != varargs) {
            return false;
        }
        if (!declaredVarargs) {
            if (declaredTypes.size() != actualTypes.size()
                    || declaredTypes.size() != actualArgumentCount) {
                return false;
            }
            return parameterTypesMatch(declaredTypes, actualTypes);
        }
        if (actualArgumentCount < declaredTypes.size() - 1) {
            return false;
        }
        if (declaredTypes.size() != actualTypes.size()) {
            return false;
        }
        for (int index = 0; index < declaredTypes.size() - 1; index++) {
            if (!sameType(declaredTypes.get(index), actualTypes.get(index))) {
                return false;
            }
        }
        return sameArrayType(
                declaredTypes.get(declaredTypes.size() - 1),
                actualTypes.get(actualTypes.size() - 1)
        );
    }

    // 作用：逐项比较参数类型；逻辑：任一位置类型不同即返回假。
    private boolean parameterTypesMatch(List<String> declaredTypes, List<String> actualTypes) {
        for (int index = 0; index < declaredTypes.size(); index++) {
            if (!sameType(declaredTypes.get(index), actualTypes.get(index))) {
                return false;
            }
        }
        return true;
    }

    // 作用：判断两个类型签名是否相同；逻辑：先规范化为签名形式，再比较字符串。
    private boolean sameType(String first, String second) {
        String left = signatureType(first);
        String right = signatureType(second);
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right);
    }

    /**
     * javac reports a varargs parameter as an array type while source
     * declarations still use {@code ...}. Keep arrays and generic structure in
     * the comparison and normalize package qualifiers recursively; stripping
     * them with {@link JavaTypeNameResolver#normalize(String)} would collapse
     * {@code String} and {@code String[]} into the same key.
     */
    // 作用：比较可变参数与数组类型；逻辑：先把 ... 统一转成 [] 再复用类型比较。
    private boolean sameArrayType(String declaredType, String actualType) {
        return sameType(varargsAsArray(declaredType), varargsAsArray(actualType));
    }

    // 作用：判断最后一个形参是否为可变参数；逻辑：检查末项声明是否以 ... 结尾。
    private boolean isVarargsParameter(List<String> declaredTypes) {
        if (declaredTypes.isEmpty()) {
            return false;
        }
        String last = declaredTypes.get(declaredTypes.size() - 1);
        return last != null && last.trim().endsWith("...");
    }

    // 作用：把可变参数声明转成数组声明；逻辑：去掉 ... 后追加 []，其他类型原样返回。
    private String varargsAsArray(String type) {
        if (type == null) {
            return null;
        }
        String value = type.trim();
        return value.endsWith("...")
                ? value.substring(0, value.length() - 3).trim() + "[]"
                : value;
    }

    // 作用：生成用于比较的类型签名；逻辑：保留数组与泛型结构，去掉每个标识符 token 的包名前缀。
    private String signatureType(String type) {
        String value = varargsAsArray(type);
        if (value == null) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        StringBuilder token = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isJavaIdentifierPart(current) || current == '.' || current == '$') {
                token.append(current);
                continue;
            }
            appendSignatureToken(result, token);
            if (!Character.isWhitespace(current)) {
                result.append(current);
            }
        }
        appendSignatureToken(result, token);
        return result.toString();
    }

    // 作用：追加一个类型标识符 token；逻辑：去掉包名前缀后写入结果并清空缓冲区。
    private void appendSignatureToken(StringBuilder result, StringBuilder token) {
        if (token.isEmpty()) {
            return;
        }
        String value = token.toString();
        int dot = value.lastIndexOf('.');
        result.append(dot < 0 ? value : value.substring(dot + 1));
        token.setLength(0);
    }

    // 作用：解析静态调用；逻辑：先解析类型全名，成功则按重载实参类型收窄，失败则归类外部边界。
    private CallResolution resolveStatic(
            CompilationUnit unit,
            CallableScope scope,
            Map<String, String> localTypes,
            String typeName,
            String methodName,
            List<Expression> arguments,
            String fallbackQname
    ) {
        String typeQname = typeNameResolver.resolve(unit, typeName);
        return typeQname == null
                ? CallResolution.unresolved(
                        fallbackQname,
                        externalTypeReason(unit, typeName, methodName)
                )
                : decideWithOverloads(
                        catalog.findMethods(typeQname, methodName, arguments.size()),
                        unit,
                        scope,
                        localTypes,
                        arguments,
                        fallbackQname,
                        typeQname,
                        methodName
                );
    }

    // 作用：解析虚调用；逻辑：先解析声明类型，成功则进入虚调用重载消歧，失败则归类外部边界。
    private CallResolution resolveVirtual(
            CompilationUnit unit,
            CallableScope scope,
            Map<String, String> localTypes,
            String typeName,
            String methodName,
            List<Expression> arguments,
            String fallbackQname
    ) {
        String typeQname = typeNameResolver.resolve(unit, typeName);
        return typeQname == null
                ? CallResolution.unresolved(
                        fallbackQname,
                        externalTypeReason(unit, typeName, methodName)
                )
                : decideVirtualWithOverloads(
                        typeQname,
                        methodName,
                        arguments.size(),
                        unit,
                        scope,
                        localTypes,
                        arguments,
                        fallbackQname
                );
    }

    /**
     * Resolves a virtual call on a declared type. When every reachable
     * declaration is abstract, the call can only execute in a subtype; a unique
     * source implementation is therefore followed so the callee value nodes are
     * reachable. Several parallel implementations stay ambiguous instead of
     * inventing one arbitrary target.
     */
    // 作用：在无实参上下文时解析虚调用；逻辑：以空编译单元、空作用域与空实参转交完整重载。
    private CallResolution decideVirtual(String typeQname, String methodName, int argumentCount, String fallbackQname) {
        return decideVirtualWithOverloads(
                typeQname, methodName, argumentCount, null, null, Map.of(), List.of(), fallbackQname
        );
    }

    // 作用：解析可能多态的虚调用；逻辑：先按实参收窄，若有方法体则定目标，抽象声明则寻找唯一具体实现。
    private CallResolution decideVirtualWithOverloads(
            String typeQname,
            String methodName,
            int argumentCount,
            CompilationUnit unit,
            CallableScope scope,
            Map<String, String> localTypes,
            List<Expression> arguments,
            String fallbackQname
    ) {
        List<SourceTypeCatalog.MethodInfo> candidates = catalog.findMethods(typeQname, methodName, argumentCount);
        CallResolution narrowed = decideByArgumentTypes(
                candidates, unit, scope, localTypes, arguments, fallbackQname
        );
        if (narrowed != null) {
            return narrowed;
        }
        if (candidates.stream().anyMatch(SourceTypeCatalog.MethodInfo::hasBody)) {
            return decide(candidates, fallbackQname, typeQname, methodName);
        }
        if (catalog.isPolymorphic(typeQname)) {
            CallResolution implementationResolution = resolveConcreteImplementations(
                    typeQname, methodName, argumentCount, candidates
            );
            if (implementationResolution != null) {
                return implementationResolution;
            }
        }
        return decide(candidates, fallbackQname, typeQname, methodName);
    }

    // 作用：处理静态调用重载；逻辑：先按实参类型尝试唯一匹配，否则进入通用候选裁决。
    private CallResolution decideWithOverloads(
            List<SourceTypeCatalog.MethodInfo> candidates,
            CompilationUnit unit,
            CallableScope scope,
            Map<String, String> localTypes,
            List<Expression> arguments,
            String fallbackQname,
            String ownerTypeQname,
            String methodName
    ) {
        CallResolution narrowed = decideByArgumentTypes(
                candidates, unit, scope, localTypes, arguments, fallbackQname
        );
        return narrowed == null
                ? decide(candidates, fallbackQname, ownerTypeQname, methodName)
                : narrowed;
    }

    /**
     * Java source frequently has several same-arity overloads. Looking only at
     * the argument count turns a statically known call such as
     * {@code transBo(ObtainAddCartParam)} into eight candidates. Narrow only
     * when every argument has a trustworthy in-repository declared type and
     * exactly one candidate remains; otherwise preserve the explicit
     * ambiguity instead of guessing.
     */
    // 作用：按实参类型收窄重载；逻辑：仅在所有参数类型足够可信且只剩唯一候选时返回精确目标。
    private CallResolution decideByArgumentTypes(
            List<SourceTypeCatalog.MethodInfo> candidates,
            CompilationUnit unit,
            CallableScope scope,
            Map<String, String> localTypes,
            List<Expression> arguments,
            String fallbackQname
    ) {
        if (candidates.size() <= 1 || unit == null || scope == null || arguments == null
                || arguments.isEmpty()) {
            return null;
        }
        List<String> actualTypes = new java.util.ArrayList<>(arguments.size());
        List<String> declaredTypes = new java.util.ArrayList<>(arguments.size());
        boolean hasKnownType = false;
        for (Expression argument : arguments) {
            String actualType = receiverTypeResolver.resolve(unit, scope, localTypes, argument);
            String declaredType = declaredType(argument, localTypes);
            if (actualType != null && !actualType.isBlank()) {
                hasKnownType = true;
            }
            if (declaredType != null && !declaredType.isBlank()) {
                hasKnownType = true;
            }
            actualTypes.add(actualType);
            declaredTypes.add(declaredType);
        }
        if (!hasKnownType) {
            return null;
        }
        List<SourceTypeCatalog.MethodInfo> matching = candidates.stream()
                .filter(candidate -> candidate.parameterTypes().size() == actualTypes.size())
                .filter(candidate -> parametersMatch(
                        unit, candidate.parameterTypes(), actualTypes, declaredTypes
                ))
                .toList();
        if (matching.size() != 1) {
            return null;
        }
        SourceTypeCatalog.MethodInfo target = matching.get(0);
        return CallResolution.compileTimeTarget(target.symbolId(), target.qname());
    }

    // 作用：判断方法形参能否匹配调用实参；逻辑：内部类型要求全名一致，外部类型按简单名比较，未知实参保持宽松。
    private boolean parametersMatch(
            CompilationUnit unit,
            List<String> parameterTypes,
            List<String> actualTypes,
            List<String> declaredTypes
    ) {
        for (int index = 0; index < parameterTypes.size(); index++) {
            String parameterType = JavaTypeNameResolver.normalize(parameterTypes.get(index));
            String declaredType = typeNameResolver.resolve(unit, parameterType);
            String actualType = actualTypes.get(index);
            String actualDeclaredType = declaredTypes.get(index);
            // An external type such as java.lang.String has no catalog entry.
            // It can still be treated as an unknown wildcard when another
            // argument provides enough evidence to eliminate all but one
            // declaration. Never guess when no argument is known.
            if (actualType == null || actualType.isBlank()) {
                if (actualDeclaredType != null && !actualDeclaredType.isBlank()
                        && !simpleType(parameterType).equals(simpleType(actualDeclaredType))) {
                    return false;
                }
                continue;
            }
            if (declaredType != null) {
                if (!declaredType.equals(actualType)) {
                    return false;
                }
                continue;
            }
            if (!simpleType(parameterType).equals(simpleType(actualType))) {
                return false;
            }
        }
        return true;
    }

    // 作用：读取实参的声明类型；逻辑：变量查局部类型表，字符串字面量视为 String，其他返回空。
    private String declaredType(Expression argument, Map<String, String> localTypes) {
        if (argument instanceof com.github.javaparser.ast.expr.NameExpr name) {
            return localTypes.get(name.getNameAsString());
        }
        if (argument instanceof com.github.javaparser.ast.expr.StringLiteralExpr) {
            return "String";
        }
        return null;
    }

    // 作用：提取类型简单名；逻辑：先去掉泛型与数组后缀，再截取最后一个点号后的名称。
    private String simpleType(String type) {
        String normalized = JavaTypeNameResolver.normalize(type);
        int separator = normalized == null ? -1 : normalized.lastIndexOf('.');
        return separator < 0 ? normalized : normalized.substring(separator + 1);
    }

    // 作用：对候选方法做最终裁决；逻辑：先去重最具体声明，空则未解析，唯一则确定目标，多个则保留候选。
    private CallResolution decide(
            List<SourceTypeCatalog.MethodInfo> candidates,
            String fallbackQname,
            String ownerTypeQname,
            String methodName
    ) {
        candidates = mostSpecific(candidates);
        if (candidates.isEmpty()) {
            return CallResolution.unresolved(
                    fallbackQname,
                    missingCalleeReason(ownerTypeQname, methodName)
            );
        }
        if (candidates.size() == 1) {
            SourceTypeCatalog.MethodInfo method = candidates.get(0);
            return CallResolution.compileTimeTarget(method.symbolId(), method.qname());
        }
        List<String> qnames = candidates.stream().map(SourceTypeCatalog.MethodInfo::qname).toList();
        return CallResolution.candidate(fallbackQname, qnames);
    }

    /**
     * A resolved owner type narrows an empty candidate list much further than
     * the old generic EXTERNAL reason: the member is either synthetic (enum
     * values/valueOf, Lombok accessors or constructors) or genuinely absent
     * from the analyzed source set.
     */
    // 作用：解释被调方法缺失原因；逻辑：依次判断外部类型、Object 方法、枚举/生成成员、外部父类与源码符号缺失。
    private String missingCalleeReason(String ownerTypeQname, String methodName) {
        if (ownerTypeQname == null || catalog.type(ownerTypeQname) == null) {
            return "EXTERNAL";
        }
        if (OBJECT_METHODS.contains(methodName)) {
            return "PLATFORM_LIBRARY";
        }
        boolean syntheticEnumMember = catalog.isEnum(ownerTypeQname)
                && ("values".equals(methodName) || "valueOf".equals(methodName));
        if (syntheticEnumMember || catalog.hasGeneratedMembers(ownerTypeQname)) {
            return "GENERATED_CODE_MISSING";
        }
        if (catalog.hasExternalSupertype(ownerTypeQname)) {
            return "CLASSPATH_MISSING";
        }
        return "CALLEE_SYMBOL_MISSING";
    }

    /**
     * A concrete class and its interface can both declare the same method.
     * Java dispatches to the most specific declaration. Remove an inherited
     * duplicate when another candidate with the same parameter signature is
     * declared by a subtype.
     */
    // 作用：移除被更具体声明覆盖的候选；逻辑：同签名时若另一候选的声明类型是当前类型的子类型，则剔除当前项。
    private List<SourceTypeCatalog.MethodInfo> mostSpecific(
            List<SourceTypeCatalog.MethodInfo> candidates
    ) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        return candidates.stream()
                .filter(candidate -> candidates.stream().noneMatch(other ->
                        !candidate.qname().equals(other.qname())
                                && candidate.parameterTypes().equals(other.parameterTypes())
                                && catalog.isSubtype(ownerType(other), ownerType(candidate))))
                .toList();
    }

    // 作用：从方法全名中提取声明类型；逻辑：截取 # 之前的部分，无分隔符时返回原值。
    private String ownerType(SourceTypeCatalog.MethodInfo method) {
        int separator = method.qname().lastIndexOf('#');
        return separator < 0 ? method.qname() : method.qname().substring(0, separator);
    }

    // 作用：取类型直接父类全名；逻辑：无父类返回 Object，优先目录全名，否则回退源码父类型表达式。
    private String firstSuperType(String typeQname) {
        SourceTypeCatalog.TypeInfo type = catalog.type(typeQname);
        if (type == null) {
            return null;
        }
        if (type.superTypes().isEmpty()) {
            return "java.lang.Object";
        }
        String qname = type.superTypes().get(0);
        if (catalog.type(qname) != null) {
            return qname;
        }
        String expression = firstSuperTypeExpression(typeQname);
        return expression == null ? qname : expression;
    }

    // 作用：取类型声明的首个父类型表达式；逻辑：无父类型时返回 Object，空白表达式返回空。
    private String firstSuperTypeExpression(String typeQname) {
        SourceTypeCatalog.TypeInfo type = catalog.type(typeQname);
        if (type == null) {
            return null;
        }
        if (type.superTypeExpressions().isEmpty()) {
            return "java.lang.Object";
        }
        String expression = type.superTypeExpressions().get(0);
        return expression == null || expression.isBlank() ? null : expression;
    }
}
