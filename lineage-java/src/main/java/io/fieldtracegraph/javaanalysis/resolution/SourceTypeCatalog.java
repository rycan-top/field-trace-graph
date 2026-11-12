package io.fieldtracegraph.javaanalysis.resolution;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.javaanalysis.JavaSymbolIdentity;
import io.fieldtracegraph.javaanalysis.semantic.source.SourcePackageIndex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：维护源码类型目录。
// 使用场景：按全限定名查找类、字段与方法。

public final class SourceTypeCatalog {
    private final Map<String, TypeInfo> types;
    private final Map<String, String> simpleNameToQname;
    private final Map<String, Map<String, String>> fieldTypesByType;
    private final Map<String, Map<String, String>> fieldTypeExpressionsByType;
    private final Map<String, List<String>> directSubtypes;
    private final Map<String, List<RawConstructor>> constructorsByType;
    private final Map<String, GeneratedConstructors> generatedConstructorsByType;
    private final Map<String, Map<String, String>> serializedFieldToFieldByType;
    private final Map<String, Map<String, Set<String>>> fieldAliasesByType;
    private final Map<String, Set<String>> ignoredSerializedFieldsByType;

    // 作用：保存源码类型目录的全部索引；逻辑：直接持有构造阶段生成的不可变映射。
    private SourceTypeCatalog(
            Map<String, TypeInfo> types,
            Map<String, String> simpleNameToQname,
            Map<String, Map<String, String>> fieldTypesByType,
            Map<String, Map<String, String>> fieldTypeExpressionsByType,
            Map<String, List<String>> directSubtypes,
            Map<String, List<RawConstructor>> constructorsByType,
            Map<String, GeneratedConstructors> generatedConstructorsByType,
            Map<String, Map<String, String>> serializedFieldToFieldByType,
            Map<String, Map<String, Set<String>>> fieldAliasesByType,
            Map<String, Set<String>> ignoredSerializedFieldsByType
    ) {
        this.types = types;
        this.simpleNameToQname = simpleNameToQname;
        this.fieldTypesByType = fieldTypesByType;
        this.fieldTypeExpressionsByType = fieldTypeExpressionsByType;
        this.directSubtypes = directSubtypes;
        this.constructorsByType = constructorsByType;
        this.generatedConstructorsByType = generatedConstructorsByType;
        this.serializedFieldToFieldByType = serializedFieldToFieldByType;
        this.fieldAliasesByType = fieldAliasesByType;
        this.ignoredSerializedFieldsByType = ignoredSerializedFieldsByType;
    }

    // 作用：从源码编译单元构建类型目录；逻辑：两遍扫描先建简单名索引，再汇总父类型、字段、方法、构造器与序列化元数据。
    public static SourceTypeCatalog build(Map<SourceFileRecord, CompilationUnit> units, SourcePackageIndex packageIndex) {
        Map<String, TypeInfo> types = new LinkedHashMap<>();
        Map<String, String> simpleNames = new HashMap<>();
        Map<String, Map<String, String>> fieldTypes = new HashMap<>();
        Map<String, Map<String, String>> fieldTypeExpressions = new HashMap<>();
        Map<String, List<String>> subtypes = new HashMap<>();
        Map<String, List<RawConstructor>> constructors = new HashMap<>();
        Map<String, GeneratedConstructors> generatedConstructors = new HashMap<>();
        Map<String, Map<String, String>> serializedFieldToField = new HashMap<>();
        Map<String, Map<String, Set<String>>> fieldAliases = new HashMap<>();
        Map<String, Set<String>> ignoredSerializedFields = new HashMap<>();
        for (var entry : units.entrySet()) {
            CompilationUnit unit = entry.getValue();
            for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
                String qname = type.getFullyQualifiedName().orElse(type.getNameAsString());
                simpleNames.putIfAbsent(type.getNameAsString(), qname);
            }
        }
        for (var entry : units.entrySet()) {
            SourceFileRecord source = entry.getKey();
            CompilationUnit unit = entry.getValue();
            String packageName = packageIndex.packageOf(source.relativePath());
            for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
                String qname = type.getFullyQualifiedName().orElse(type.getNameAsString());
                List<MethodInfo> methods = new ArrayList<>();
                for (BodyDeclaration<?> member : type.getMembers()) {
                    if (member instanceof MethodDeclaration method) {
                        methods.add(methodInfo(source.moduleId(), qname, method,
                                declaredType -> resolveFieldType(unit, packageName, simpleNames, declaredType)));
                    } else if (member instanceof ConstructorDeclaration constructor) {
                        if (isCompactRecordConstructor(type, constructor)) {
                            RecordDeclaration record = (RecordDeclaration) type;
                            methods.add(recordCanonicalConstructor(source.moduleId(), qname, record));
                            constructors.computeIfAbsent(qname, key -> new ArrayList<>())
                                    .add(recordCanonicalRawConstructor(qname, record));
                        } else {
                            methods.add(methodInfo(source.moduleId(), qname, constructor, declaredType -> null));
                            constructors.computeIfAbsent(qname, key -> new ArrayList<>())
                                    .add(rawConstructor(
                                            qname,
                                            constructor,
                                            superTypeQnames(type, unit, packageName, simpleNames)
                                    ));
                        }
                    }
                }
                if (JavaSymbolIdentity.needsImplicitConstructor(type)
                        && methods.stream().noneMatch(method -> "<init>".equals(method.simpleName()))) {
                    methods.add(new MethodInfo(
                            JavaSymbolIdentity.implicitConstructorQname(qname),
                            JavaSymbolIdentity.implicitConstructorSymbolId(source.moduleId(), qname),
                            "<init>",
                            List.of(),
                            null,
                            null,
                            List.of(),
                            List.of(),
                            true
                    ));
                }
                if (type instanceof RecordDeclaration record
                        && !hasCanonicalRecordConstructor(record, methods)) {
                    methods.add(recordCanonicalConstructor(source.moduleId(), qname, record));
                    constructors.computeIfAbsent(qname, key -> new ArrayList<>())
                            .add(recordCanonicalRawConstructor(qname, record));
                }
                Map<String, String> fields = new HashMap<>();
                Map<String, String> fieldExpressions = new HashMap<>();
                List<String> fieldOrder = new ArrayList<>();
                List<String> requiredFields = new ArrayList<>();
                Map<String, String> serializedNames = new LinkedHashMap<>();
                Map<String, Set<String>> aliasesByField = new LinkedHashMap<>();
                Set<String> ignoredFields = new LinkedHashSet<>();
                if (type instanceof RecordDeclaration record) {
                    for (var component : record.getParameters()) {
                        String name = component.getNameAsString();
                        String typeExpression = component.getType().asString();
                        fields.putIfAbsent(
                                name,
                                resolveFieldType(unit, packageName, simpleNames, typeExpression)
                        );
                        fieldExpressions.putIfAbsent(name, typeExpression);
                        fieldOrder.add(name);
                        requiredFields.add(name);
                        collectSerializationMetadata(
                                component.getAnnotations(),
                                name,
                                serializedNames,
                                aliasesByField,
                                ignoredFields
                        );
                    }
                }
                for (FieldDeclaration field : type.getFields()) {
                    for (VariableDeclarator variable : field.getVariables()) {
                        String fieldName = variable.getNameAsString();
                        fields.putIfAbsent(fieldName, resolveFieldType(unit, packageName, simpleNames, field.getElementType().asString()));
                        fieldExpressions.putIfAbsent(variable.getNameAsString(), field.getElementType().asString());
                        collectSerializationMetadata(
                                field.getAnnotations(),
                                fieldName,
                                serializedNames,
                                aliasesByField,
                                ignoredFields
                        );
                        if (!field.isStatic()) {
                            if (!fieldOrder.contains(variable.getNameAsString())) {
                                fieldOrder.add(variable.getNameAsString());
                            }
                            if (field.isFinal() || hasAnnotation(field, "NonNull")
                                    ) {
                                if (!requiredFields.contains(variable.getNameAsString())) {
                                    requiredFields.add(variable.getNameAsString());
                                }
                            }
                        }
                    }
                }
                for (MethodDeclaration method : type.getMethods()) {
                    String property = serializationProperty(method);
                    if (property == null) {
                        continue;
                    }
                    collectSerializationMetadata(
                            method.getAnnotations(),
                            property,
                            serializedNames,
                            aliasesByField,
                            ignoredFields
                    );
                }
                Map<String, String> serializedToField = new LinkedHashMap<>();
                serializedNames.forEach((fieldName, serializedName) -> {
                    if (serializedName != null && !serializedName.isBlank()) {
                        serializedToField.putIfAbsent(serializedName, fieldName);
                    }
                });
                aliasesByField.forEach((fieldName, aliases) ->
                        aliases.forEach(alias -> {
                            if (alias != null && !alias.isBlank()) {
                                serializedToField.putIfAbsent(alias, fieldName);
                            }
                        }));
                serializedFieldToField.put(qname, Map.copyOf(serializedToField));
                Map<String, Set<String>> immutableAliases = new HashMap<>();
                aliasesByField.forEach((fieldName, aliases) ->
                        immutableAliases.put(fieldName, Set.copyOf(aliases)));
                fieldAliases.put(qname, Map.copyOf(immutableAliases));
                ignoredSerializedFields.put(qname, Set.copyOf(ignoredFields));
                List<String> superTypes = superTypeQnames(type, unit, packageName, simpleNames);
                types.put(qname, new TypeInfo(
                        qname,
                        JavaSymbolIdentity.classSymbolId(source.moduleId(), qname),
                        superTypes,
                        superTypeExpressions(type, unit, packageName, simpleNames),
                        methods,
                        isPolymorphic(type),
                        typeParameters(type),
                        type instanceof EnumDeclaration,
                        hasGeneratedMembers(type)
                ));
                fieldTypes.put(qname, Map.copyOf(fields));
                fieldTypeExpressions.put(qname, Map.copyOf(fieldExpressions));
                generatedConstructors.put(qname, generatedConstructors(type, fieldOrder, requiredFields));
                for (String superType : superTypes) {
                    subtypes.computeIfAbsent(superType, key -> new ArrayList<>()).add(qname);
                }
            }
        }
        Map<String, List<String>> immutableSubtypes = new HashMap<>();
        subtypes.forEach((key, value) -> immutableSubtypes.put(key, List.copyOf(value)));
        Map<String, List<RawConstructor>> immutableConstructors = new HashMap<>();
        constructors.forEach((key, value) -> immutableConstructors.put(key, List.copyOf(value)));
        return new SourceTypeCatalog(
                Map.copyOf(types),
                Map.copyOf(simpleNames),
                Map.copyOf(fieldTypes),
                Map.copyOf(fieldTypeExpressions),
                Map.copyOf(immutableSubtypes),
                Map.copyOf(immutableConstructors),
                Map.copyOf(generatedConstructors),
                Map.copyOf(serializedFieldToField),
                Map.copyOf(fieldAliases),
                Map.copyOf(ignoredSerializedFields)
        );
    }

    // 作用：按全限定名查询类型；逻辑：直接从类型映射取值，未知类型返回空。
    public TypeInfo type(String qname) {
        return types.get(qname);
    }

    // 作用：判断类型是否为枚举；逻辑：查到类型信息后读取枚举标记。
    public boolean isEnum(String typeQname) {
        TypeInfo type = types.get(typeQname);
        return type != null && type.enumType();
    }

    /**
     * Lombok and similar processors add accessors, builders and constructors
     * after parsing. A missing member on such a type is a different boundary
     * from a missing user-written method.
     */
    // 作用：判断类型是否含框架生成成员；逻辑：读取类型信息中的生成成员标记。
    public boolean hasGeneratedMembers(String typeQname) {
        TypeInfo type = types.get(typeQname);
        return type != null && type.generatedMembers();
    }

    /**
     * A member that is absent from the source type may still be inherited from
     * a superclass or interface outside the analysed source set. In that case
     * the actionable evidence is the owning classpath entry, not a missing
     * source declaration.
     */
    // 作用：判断类型是否继承源码外类型；逻辑：初始化访问集合后递归检查父类型链。
    public boolean hasExternalSupertype(String typeQname) {
        return hasExternalSupertype(typeQname, new HashSet<>());
    }

    // 作用：递归检查父类型是否越出源码集；逻辑：目录中不存在的父类型视为外部，存在则继续向上检查并防止环。
    private boolean hasExternalSupertype(String typeQname, Set<String> visited) {
        if (typeQname == null || !visited.add(typeQname)) {
            return false;
        }
        TypeInfo type = types.get(typeQname);
        if (type == null) {
            return true;
        }
        for (String superType : type.superTypes()) {
            if (hasExternalSupertype(superType, visited)) {
                return true;
            }
        }
        return false;
    }

    // 作用：判断是否存在指定参数数量的生成构造器；逻辑：按全参、必填参和无参三种 Lombok 形态匹配。
    public boolean hasGeneratedConstructor(String typeQname, int argumentCount) {
        GeneratedConstructors generated = generatedConstructorsByType.get(typeQname);
        if (generated == null) {
            return false;
        }
        return (generated.allArgs() && generated.allArgsConstructor().size() == argumentCount)
                || (generated.requiredArgs()
                && generated.requiredArgsConstructor().size() == argumentCount)
                || (generated.noArgs() && argumentCount == 0);
    }

    // 作用：按简单名解析类型全名；逻辑：从全局简单名索引取值，未命中返回空。
    public String resolveTypeName(String simpleName) {
        return simpleNameToQname.get(simpleName);
    }

    // 作用：查询类型自身声明的字段类型；逻辑：先从字段类型表取该类型的映射，再按字段名取值。
    public String fieldType(String typeQname, String fieldName) {
        Map<String, String> fields = fieldTypesByType.get(typeQname);
        return fields == null ? null : fields.get(fieldName);
    }

    /**
     * Resolves a field through the same inheritance chain used by field-name
     * checks. Direct field lookup alone loses receiver types for inherited
     * fields and then falls back to method-name-only accessor inference.
     */
    // 作用：在继承链中查询字段类型；逻辑：初始化访问集合后递归查找。
    public String fieldTypeInHierarchy(String typeQname, String fieldName) {
        return fieldTypeInHierarchy(typeQname, fieldName, new HashSet<>());
    }

    // 作用：递归查找字段类型；逻辑：先查当前类型字段，未命中再沿父类型查找并防止循环。
    private String fieldTypeInHierarchy(String typeQname, String fieldName, Set<String> visited) {
        if (typeQname == null || !visited.add(typeQname)) {
            return null;
        }
        Map<String, String> fields = fieldTypesByType.get(typeQname);
        if (fields != null && fields.containsKey(fieldName)) {
            return fields.get(fieldName);
        }
        TypeInfo type = types.get(typeQname);
        if (type != null) {
            for (String superType : type.superTypes()) {
                String result = fieldTypeInHierarchy(superType, fieldName, visited);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    // 作用：查询字段的原始类型表达式；逻辑：从保留泛型的字段表达式表按类型与字段名取值。
    public String fieldTypeExpression(String typeQname, String fieldName) {
        Map<String, String> fields = fieldTypeExpressionsByType.get(typeQname);
        return fields == null ? null : fields.get(fieldName);
    }

    // 作用：在继承链中查询字段类型表达式；逻辑：初始化访问集合后递归查找。
    public String fieldTypeExpressionInHierarchy(String typeQname, String fieldName) {
        return fieldTypeExpressionInHierarchy(typeQname, fieldName, new HashSet<>());
    }

    // 作用：递归查找字段类型表达式；逻辑：先查当前类型，未命中再沿父类型继续并防止循环。
    private String fieldTypeExpressionInHierarchy(
            String typeQname,
            String fieldName,
            Set<String> visited
    ) {
        if (typeQname == null || !visited.add(typeQname)) {
            return null;
        }
        String expression = fieldTypeExpression(typeQname, fieldName);
        if (expression != null) {
            return expression;
        }
        TypeInfo type = types.get(typeQname);
        if (type != null) {
            for (String superType : type.superTypes()) {
                String result = fieldTypeExpressionInHierarchy(superType, fieldName, visited);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * Resolves a JavaBean property to its declared field. This handles
     * unconventional but valid source such as a field named {@code IsGlobal}
     * exposed through {@code isGlobal()}, whose business property is
     * {@code global}.
     */
    // 作用：按 JavaBean 属性名查字段类型；逻辑：初始化访问集合后递归匹配规范化属性名。
    public String fieldTypeForPropertyInHierarchy(String typeQname, String property) {
        return fieldTypeForPropertyInHierarchy(typeQname, property, new HashSet<>());
    }

    // 作用：递归按属性名匹配字段；逻辑：比较字段名的规范化属性名，未命中再沿父类型查找。
    private String fieldTypeForPropertyInHierarchy(
            String typeQname,
            String property,
            Set<String> visited
    ) {
        if (typeQname == null || !visited.add(typeQname)) {
            return null;
        }
        Map<String, String> fields = fieldTypesByType.get(typeQname);
        if (fields != null) {
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                if (property.equals(AccessorFieldIdentity.canonicalPropertyName(entry.getKey()))) {
                    return entry.getValue();
                }
            }
        }
        TypeInfo type = types.get(typeQname);
        if (type != null) {
            for (String superType : type.superTypes()) {
                String result = fieldTypeForPropertyInHierarchy(superType, property, visited);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * Returns declared and inherited field names. Bean copy has runtime
     * property semantics, but only fields visible in the analysed source set
     * can be expanded without inventing a member.
     */
    // 作用：收集类型可见字段名；逻辑：递归汇总本类与父类字段并返回不可变集合。
    public Set<String> fieldNames(String typeQname) {
        Set<String> names = new HashSet<>();
        collectFieldNames(typeQname, names, new HashSet<>());
        return Set.copyOf(names);
    }

    /**
     * Returns JSON/XML serialized names and aliases declared for a field. The
     * graph keeps Java field identities, while external boundaries may refer
     * to the same value through one of these names.
     */
    // 作用：查询字段的序列化别名；逻辑：从别名表取值，缺失时返回空集合。
    public Set<String> fieldAliases(String typeQname, String fieldName) {
        Map<String, Set<String>> aliases = fieldAliasesByType.get(typeQname);
        if (aliases == null || fieldName == null) {
            return Set.of();
        }
        return aliases.getOrDefault(fieldName, Set.of());
    }

    /**
     * Maps a serialized name or alias back to the declared Java field.
     */
    // 作用：把序列化名称映射回 Java 字段；逻辑：初始化访问集合后递归查询。
    public String fieldForSerializedName(String typeQname, String serializedName) {
        return fieldForSerializedName(typeQname, serializedName, new HashSet<>());
    }

    // 作用：递归解析序列化名称；逻辑：先查当前类型映射，未命中再沿父类型查找并防止循环。
    private String fieldForSerializedName(
            String typeQname,
            String serializedName,
            Set<String> visited
    ) {
        if (typeQname == null || serializedName == null || !visited.add(typeQname)) {
            return null;
        }
        Map<String, String> names = serializedFieldToFieldByType.get(typeQname);
        if (names != null && names.containsKey(serializedName)) {
            return names.get(serializedName);
        }
        TypeInfo type = types.get(typeQname);
        if (type != null) {
            for (String superType : type.superTypes()) {
                String resolved = fieldForSerializedName(superType, serializedName, visited);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return null;
    }

    // 作用：判断字段是否被序列化忽略；逻辑：初始化访问集合后递归查询。
    public boolean isSerializationIgnored(String typeQname, String fieldName) {
        return isSerializationIgnored(typeQname, fieldName, new HashSet<>());
    }

    // 作用：递归判断序列化忽略标记；逻辑：先查当前类型忽略集合，未命中再沿父类型检查。
    private boolean isSerializationIgnored(
            String typeQname,
            String fieldName,
            Set<String> visited
    ) {
        if (typeQname == null || fieldName == null || !visited.add(typeQname)) {
            return false;
        }
        Set<String> ignored = ignoredSerializedFieldsByType.get(typeQname);
        if (ignored != null && ignored.contains(fieldName)) {
            return true;
        }
        TypeInfo type = types.get(typeQname);
        if (type != null) {
            for (String superType : type.superTypes()) {
                if (isSerializationIgnored(superType, fieldName, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    // 作用：递归汇总字段名；逻辑：加入当前类型字段后沿父类型收集，并用访问集合避免环。
    private void collectFieldNames(String typeQname, Set<String> names, Set<String> visited) {
        if (typeQname == null || !visited.add(typeQname)) {
            return;
        }
        Map<String, String> fields = fieldTypesByType.get(typeQname);
        if (fields != null) {
            names.addAll(fields.keySet());
        }
        TypeInfo type = types.get(typeQname);
        if (type != null) {
            for (String superType : type.superTypes()) {
                collectFieldNames(superType, names, visited);
            }
        }
    }

    /**
     * Resolves a constructor by arity and, when the caller can prove argument
     * types, by parameter type. Two same-arity constructors must never be
     * merged by declaration order.
     */
    // 作用：按实参类型解析构造器字段绑定；逻辑：按参数数量筛选，歧义时按类型匹配，缺失时回退到生成构造器。
    public Map<Integer, String> constructorFieldBindings(String typeQname, List<String> argumentTypes) {
        int argumentCount = argumentTypes.size();
        List<RawConstructor> candidates = constructorsByType.getOrDefault(typeQname, List.of())
                .stream()
                .filter(constructor -> constructor.parameterNames().size() == argumentCount)
                .toList();
        if (candidates.size() > 1) {
            if (argumentTypes.stream().anyMatch(type -> type == null || type.isBlank())) {
                return Map.of();
            }
            candidates = candidates.stream()
                    .filter(constructor -> parametersMatch(constructor.parameterTypes(), argumentTypes))
                    .toList();
        }
        if (candidates.size() == 1) {
            return resolveConstructorFields(candidates.get(0), new HashSet<>());
        }
        if (candidates.isEmpty()) {
            GeneratedConstructors generated = generatedConstructorsByType.get(typeQname);
            if (generated == null) {
                return Map.of();
            }
            if (generated.allArgs() && generated.allArgsConstructor().size() == argumentCount) {
                return generated.allArgsConstructor();
            }
            if (generated.requiredArgs()
                    && generated.requiredArgsConstructor().size() == argumentCount) {
                return generated.requiredArgsConstructor();
            }
        }
        return Map.of();
    }

    // 作用：查询方法返回类型；逻辑：遍历匹配方法并返回首个非空返回类型。
    public String methodReturnType(String typeQname, String methodName, int argumentCount) {
        for (MethodInfo method : findMethods(typeQname, methodName, argumentCount)) {
            if (method.returnType() != null) {
                return method.returnType();
            }
        }
        return null;
    }

    // 作用：查询保留泛型的返回类型表达式；逻辑：遍历匹配方法并返回首个非空表达式。
    public String methodReturnTypeExpression(String typeQname, String methodName, int argumentCount) {
        for (MethodInfo method : findMethods(typeQname, methodName, argumentCount)) {
            if (method.returnTypeExpression() != null) {
                return method.returnTypeExpression();
            }
        }
        return null;
    }

    // 作用：读取类型参数名；逻辑：类型不存在时返回空列表。
    public List<String> typeParameters(String typeQname) {
        TypeInfo type = types.get(typeQname);
        return type == null ? List.of() : type.typeParameters();
    }

    // 作用：读取父类型表达式；逻辑：类型不存在时返回空列表。
    public List<String> superTypeExpressions(String typeQname) {
        TypeInfo type = types.get(typeQname);
        return type == null ? List.of() : type.superTypeExpressions();
    }

    // 作用：查询类型自身声明的方法；逻辑：按方法名与参数数量过滤，并排除构造器。
    public List<MethodInfo> declaredMethods(String typeQname, String methodName, int argumentCount) {
        TypeInfo type = types.get(typeQname);
        if (type == null) {
            return List.of();
        }
        return type.methods().stream()
                .filter(method -> !"<init>".equals(method.simpleName()))
                .filter(method -> method.simpleName().equals(methodName))
                .filter(method -> argumentCount < 0 || method.parameterTypes().size() == argumentCount)
                .toList();
    }

    // 作用：判断类型是否多态；逻辑：读取类型信息中的多态标记。
    public boolean isPolymorphic(String typeQname) {
        TypeInfo type = types.get(typeQname);
        return type != null && type.polymorphic();
    }

    /**
     * Returns whether {@code candidate} is the same type as, or a subtype of,
     * {@code expectedSupertype}. Method lookup uses this to discard an
     * inherited declaration when the receiver type contains the override.
     */
    // 作用：判断类型是否为指定父类型的子类型；逻辑：广度优先遍历父类型链并防止重复访问。
    public boolean isSubtype(String candidate, String expectedSupertype) {
        if (candidate == null || expectedSupertype == null) {
            return false;
        }
        if (candidate.equals(expectedSupertype)) {
            return true;
        }
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(List.of(candidate));
        java.util.Set<String> visited = new java.util.HashSet<>();
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            TypeInfo type = types.get(current);
            if (type == null) {
                continue;
            }
            for (String superType : type.superTypes()) {
                if (expectedSupertype.equals(superType)) {
                    return true;
                }
                queue.addLast(superType);
            }
        }
        return false;
    }

    /**
     * Finds the concrete methods that can receive a virtual call declared on
     * {@code typeQname}. Direct subtypes are visited before transitive ones so
     * the nearest override wins; unrelated parallel implementations remain
     * ambiguous and are reported as candidates by the caller.
     */
    // 作用：查找虚调用的具体实现；逻辑：按直接子类型优先的广度遍历，收集有方法体的同名同参数方法。
    public List<MethodInfo> findConcreteOverrides(String typeQname, String methodName, int argumentCount) {
        List<MethodInfo> result = new ArrayList<>();
        java.util.Set<String> visitedTypes = new java.util.HashSet<>();
        java.util.Set<String> visitedSymbols = new java.util.HashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(
                directSubtypes.getOrDefault(typeQname, List.of())
        );
        while (!queue.isEmpty()) {
            String subtype = queue.removeFirst();
            if (!visitedTypes.add(subtype)) {
                continue;
            }
            TypeInfo type = types.get(subtype);
            if (type != null) {
                for (MethodInfo method : type.methods()) {
                    if (method.hasBody()
                            && method.simpleName().equals(methodName)
                            && method.parameterTypes().size() == argumentCount
                            && visitedSymbols.add(method.symbolId())) {
                        result.add(method);
                    }
                }
            }
            queue.addAll(directSubtypes.getOrDefault(subtype, List.of()));
        }
        return result;
    }

    // 作用：在继承链中查找方法；逻辑：初始化访问集合后递归收集。
    public List<MethodInfo> findMethods(String typeQname, String methodName, int argumentCount) {
        List<MethodInfo> result = new ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        collectMethods(typeQname, methodName, argumentCount, visited, result);
        return result;
    }

    // 作用：按名称查找任意参数数量的方法；逻辑：以参数数量通配值递归收集。
    public List<MethodInfo> findMethods(String typeQname, String methodName) {
        List<MethodInfo> result = new ArrayList<>();
        collectMethods(typeQname, methodName, -1, new java.util.HashSet<>(), result);
        return result;
    }

    // 作用：查找指定参数数量的构造器；逻辑：初始化访问集合后收集。
    public List<MethodInfo> findConstructors(String typeQname, int argumentCount) {
        List<MethodInfo> result = new ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        collectConstructors(typeQname, argumentCount, visited, result);
        return result;
    }

    // 作用：查找类型的所有构造器；逻辑：以参数数量通配值收集。
    public List<MethodInfo> findConstructors(String typeQname) {
        List<MethodInfo> result = new ArrayList<>();
        collectConstructors(typeQname, -1, new java.util.HashSet<>(), result);
        return result;
    }

    // 作用：递归收集构造器；逻辑：加入本类匹配构造器并防止类型环，构造器不沿父类型继承。
    private void collectConstructors(String typeQname, int argumentCount, java.util.Set<String> visited, List<MethodInfo> result) {
        if (typeQname == null || !visited.add(typeQname)) {
            return;
        }
        TypeInfo type = types.get(typeQname);
        if (type == null) {
            return;
        }
        for (MethodInfo method : type.methods()) {
            if ("<init>".equals(method.simpleName())
                    && (argumentCount < 0 || method.parameterTypes().size() == argumentCount)) {
                result.add(method);
            }
        }
    }

    // 作用：递归收集方法；逻辑：先加入本类匹配方法，再沿父类型继续查找并防止重复访问。
    private void collectMethods(String typeQname, String methodName, int argumentCount, java.util.Set<String> visited, List<MethodInfo> result) {
        if (typeQname == null || !visited.add(typeQname)) {
            return;
        }
        TypeInfo type = types.get(typeQname);
        if (type == null) {
            return;
        }
        for (MethodInfo method : type.methods()) {
            if (!"<init>".equals(method.simpleName())
                    && method.simpleName().equals(methodName)
                    && (argumentCount < 0 || method.parameterTypes().size() == argumentCount)) {
                result.add(method);
            }
        }
        for (String superType : type.superTypes()) {
            collectMethods(superType, methodName, argumentCount, visited, result);
        }
    }

    // 作用：解析构造器参数到字段的绑定；逻辑：合并直接赋值与 this/super 委托构造器的绑定，并防止递归。
    private Map<Integer, String> resolveConstructorFields(
            RawConstructor constructor,
            Set<String> visiting
    ) {
        String visitKey = constructor.typeQname() + "/" + constructor.parameterNames().size();
        if (!visiting.add(visitKey)) {
            return constructor.directFields();
        }
        Map<Integer, String> resolved = new LinkedHashMap<>(constructor.directFields());
        if (constructor.delegateTypeQname() != null
                && !constructor.delegateArgumentIndexes().isEmpty()) {
            List<RawConstructor> delegates = constructorsByType
                    .getOrDefault(constructor.delegateTypeQname(), List.of())
                    .stream()
                    .filter(candidate -> candidate.parameterNames().size()
                            == constructor.delegateArgumentIndexes().size())
                    .toList();
            if (delegates.size() > 1) {
                List<String> delegateTypes = constructor.delegateArgumentIndexes().stream()
                        .map(index -> index >= 0 && index < constructor.parameterTypes().size()
                                ? constructor.parameterTypes().get(index)
                                : null)
                        .toList();
                if (delegateTypes.stream().allMatch(type -> type != null && !type.isBlank())) {
                    delegates = delegates.stream()
                            .filter(candidate -> parametersMatch(candidate.parameterTypes(), delegateTypes))
                            .toList();
                }
            }
            if (delegates.size() == 1) {
                Map<Integer, String> inherited = resolveConstructorFields(delegates.get(0), visiting);
                for (Map.Entry<Integer, String> binding : inherited.entrySet()) {
                    int delegateIndex = binding.getKey();
                    if (delegateIndex >= constructor.delegateArgumentIndexes().size()) {
                        continue;
                    }
                    int constructorParameter = constructor.delegateArgumentIndexes().get(delegateIndex);
                    if (constructorParameter >= 0) {
                        resolved.putIfAbsent(constructorParameter, binding.getValue());
                    }
                }
            }
        }
        visiting.remove(visitKey);
        return Map.copyOf(resolved);
    }

    // 作用：从源码构造器提取原始绑定信息；逻辑：记录参数、this 字段赋值以及首个 this/super 委托调用的参数映射。
    private static RawConstructor rawConstructor(
            String typeQname,
            ConstructorDeclaration constructor,
            List<String> superTypes
    ) {
        List<String> parameterNames = constructor.getParameters().stream()
                .map(parameter -> parameter.getNameAsString())
                .toList();
        List<String> parameterTypes = JavaSymbolIdentity.parameterTypes(constructor);
        Map<Integer, String> directFields = new LinkedHashMap<>();
        for (AssignExpr assignment : constructor.findAll(AssignExpr.class)) {
            if (assignment.findAncestor(CallableDeclaration.class).orElse(null) != constructor
                    || !(assignment.getTarget() instanceof FieldAccessExpr field)
                    || !(field.getScope() instanceof ThisExpr)
                    || !(assignment.getValue() instanceof NameExpr value)) {
                continue;
            }
            int parameterIndex = parameterNames.indexOf(value.getNameAsString());
            if (parameterIndex >= 0) {
                directFields.putIfAbsent(parameterIndex, field.getNameAsString());
            }
        }
        ExplicitConstructorInvocationStmt delegate = constructor.getBody().getStatements().stream()
                .filter(ExplicitConstructorInvocationStmt.class::isInstance)
                .map(ExplicitConstructorInvocationStmt.class::cast)
                .findFirst()
                .orElse(null);
        String delegateTypeQname = null;
        List<Integer> delegateArgumentIndexes = List.of();
        if (delegate != null) {
            delegateTypeQname = delegate.isThis()
                    ? typeQname
                    : superTypes.isEmpty() ? null : superTypes.get(0);
            if (delegateTypeQname != null) {
                List<Integer> indexes = new ArrayList<>();
                for (var argument : delegate.getArguments()) {
                    if (!(argument instanceof NameExpr name)) {
                        indexes.clear();
                        break;
                    }
                    int parameterIndex = parameterNames.indexOf(name.getNameAsString());
                    if (parameterIndex < 0) {
                        indexes.clear();
                        break;
                    }
                    indexes.add(parameterIndex);
                }
                delegateArgumentIndexes = List.copyOf(indexes);
            }
        }
        return new RawConstructor(
                typeQname,
                parameterNames,
                parameterTypes,
                Map.copyOf(directFields),
                delegateTypeQname,
                delegateArgumentIndexes
        );
    }

    // 作用：创建 record 规范构造器信息；逻辑：按组件类型生成构造器全名、符号 ID 与参数表达式。
    private static MethodInfo recordCanonicalConstructor(
            String moduleId,
            String typeQname,
            RecordDeclaration record
    ) {
        List<String> parameterTypes = JavaSymbolIdentity.recordCanonicalParameterTypes(record);
        List<String> parameterTypeExpressions = record.getParameters().stream()
                .map(parameter -> parameter.getType().asString())
                .toList();
        String qname = JavaSymbolIdentity.recordCanonicalConstructorQname(typeQname, parameterTypes);
        return new MethodInfo(
                qname,
                JavaSymbolIdentity.callableSymbolId(moduleId, qname),
                "<init>",
                parameterTypes,
                null,
                null,
                parameterTypeExpressions,
                List.of(),
                true
        );
    }

    // 作用：判断是否为 record 紧凑构造器；逻辑：委托符号身份工具进行判定。
    private static boolean isCompactRecordConstructor(
            TypeDeclaration<?> type,
            ConstructorDeclaration constructor
    ) {
        return JavaSymbolIdentity.isCompactRecordConstructor(type, constructor);
    }

    // 作用：判断是否已存在 record 规范构造器；逻辑：按组件数量与类型逐项比较现有构造器。
    private static boolean hasCanonicalRecordConstructor(
            RecordDeclaration record,
            List<MethodInfo> methods
    ) {
        List<String> componentTypes = JavaSymbolIdentity.recordCanonicalParameterTypes(record);
        return methods.stream()
                .filter(method -> "<init>".equals(method.simpleName()))
                .filter(method -> method.parameterTypes().size() == componentTypes.size())
                .anyMatch(method -> {
                    for (int index = 0; index < componentTypes.size(); index++) {
                        String declared = method.parameterTypes().get(index);
                        if (declared == null || !sameRecordType(declared, componentTypes.get(index))) {
                            return false;
                        }
                    }
                    return true;
                });
    }

    // 作用：创建 record 规范构造器的原始绑定；逻辑：组件按位置直接绑定到同名字段。
    private static RawConstructor recordCanonicalRawConstructor(
            String typeQname,
            RecordDeclaration record
    ) {
        List<String> parameterNames = record.getParameters().stream()
                .map(parameter -> parameter.getNameAsString())
                .toList();
        List<String> parameterTypes = JavaSymbolIdentity.recordCanonicalParameterTypes(record);
        Map<Integer, String> directFields = new LinkedHashMap<>();
        for (int index = 0; index < parameterNames.size(); index++) {
            directFields.put(index, parameterNames.get(index));
        }
        return new RawConstructor(
                typeQname,
                parameterNames,
                parameterTypes,
                Map.copyOf(directFields),
                null,
                List.of()
        );
    }

    // 作用：比较两个 record 参数类型；逻辑：去掉空白后做字符串相等判断。
    private static boolean sameRecordType(String first, String second) {
        return first != null && second != null
                && first.replace(" ", "").equals(second.replace(" ", ""));
    }

    // 作用：推断 Lombok 生成的构造器形态；逻辑：按全参、必填参、无参注解生成对应位置绑定。
    private static GeneratedConstructors generatedConstructors(
            TypeDeclaration<?> type,
            List<String> fieldOrder,
            List<String> requiredFields
    ) {
        boolean allArgs = hasAnnotation(type, "AllArgsConstructor")
                || hasAnnotation(type, "Builder")
                || hasAnnotation(type, "Value");
        boolean requiredArgs = hasAnnotation(type, "RequiredArgsConstructor");
        return new GeneratedConstructors(
                allArgs ? positionalBindings(fieldOrder) : Map.of(),
                allArgs,
                requiredArgs ? positionalBindings(requiredFields) : Map.of(),
                requiredArgs,
                hasAnnotation(type, "NoArgsConstructor")
        );
    }

    // 作用：判断类型是否由 Lombok 生成成员；逻辑：检查 Data、Getter、Setter、Value、Builder 注解。
    private static boolean hasGeneratedMembers(TypeDeclaration<?> type) {
        return hasAnnotation(type, "Data")
                || hasAnnotation(type, "Getter")
                || hasAnnotation(type, "Setter")
                || hasAnnotation(type, "Value")
                || hasAnnotation(type, "Builder");
    }

    // 作用：按字段顺序生成位置绑定；逻辑：下标依次对应字段名并返回不可变映射。
    private static Map<Integer, String> positionalBindings(List<String> fields) {
        Map<Integer, String> result = new LinkedHashMap<>();
        for (int index = 0; index < fields.size(); index++) {
            result.put(index, fields.get(index));
        }
        return Map.copyOf(result);
    }

    // 作用：判断声明是否带指定简单名注解；逻辑：规范化注解名后逐个比较。
    private static boolean hasAnnotation(BodyDeclaration<?> declaration, String simpleName) {
        return declaration.getAnnotations().stream()
                .map(annotation -> simpleAnnotationName(annotation.getNameAsString()))
                .anyMatch(simpleName::equals);
    }

    // 作用：提取注解简单名；逻辑：截取最后一个点号后的名称。
    private static String simpleAnnotationName(String annotationName) {
        int separator = annotationName.lastIndexOf('.');
        return separator < 0 ? annotationName : annotationName.substring(separator + 1);
    }

    // 作用：从序列化方法名推导属性名；逻辑：先按 getter 规则解析，失败再按 setter 规则解析。
    private static String serializationProperty(MethodDeclaration method) {
        String getter = AccessorFieldIdentity.propertyFromAccessor(
                method.getNameAsString(), true
        );
        if (getter != null) {
            return getter;
        }
        return AccessorFieldIdentity.propertyFromAccessor(
                method.getNameAsString(), false
        );
    }

    // 作用：收集字段或方法的序列化元数据；逻辑：识别忽略注解、序列化名与别名，并写入对应索引。
    private static void collectSerializationMetadata(
            List<AnnotationExpr> annotations,
            String fieldName,
            Map<String, String> serializedNames,
            Map<String, Set<String>> aliasesByField,
            Set<String> ignoredFields
    ) {
        for (AnnotationExpr annotation : annotations) {
            String simpleName = simpleAnnotationName(annotation.getNameAsString());
            if ("JsonIgnore".equals(simpleName)) {
                ignoredFields.add(fieldName);
                continue;
            }
            if ("JSONField".equals(simpleName)) {
                String name = annotationString(annotation, "name");
                if (name != null && !name.isBlank()) {
                    serializedNames.putIfAbsent(fieldName, name);
                }
                addAliases(aliasesByField, fieldName, annotationStrings(annotation, "alternateNames"));
                if ("false".equals(annotationString(annotation, "serialize"))) {
                    ignoredFields.add(fieldName);
                }
                continue;
            }
            if ("JsonProperty".equals(simpleName) || "SerializedName".equals(simpleName)) {
                String name = annotationString(annotation, "value");
                if (name != null && !name.isBlank()) {
                    serializedNames.putIfAbsent(fieldName, name);
                }
                addAliases(aliasesByField, fieldName, annotationStrings(annotation, "alternate"));
                continue;
            }
            if ("JsonAlias".equals(simpleName)) {
                addAliases(aliasesByField, fieldName, annotationStrings(annotation, "value"));
            }
        }
    }

    // 作用：追加字段别名；逻辑：过滤空别名后按字段名合并到别名集合。
    private static void addAliases(
            Map<String, Set<String>> aliasesByField,
            String fieldName,
            List<String> aliases
    ) {
        for (String alias : aliases) {
            if (alias != null && !alias.isBlank()) {
                aliasesByField.computeIfAbsent(fieldName, ignored -> new LinkedHashSet<>())
                        .add(alias);
            }
        }
    }

    // 作用：读取注解的字符串成员；逻辑：兼容单值注解的 value 与普通注解的指定成员。
    private static String annotationString(AnnotationExpr annotation, String memberName) {
        if (annotation instanceof SingleMemberAnnotationExpr single
                && "value".equals(memberName)) {
            return stringValue(single.getMemberValue());
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (memberName.equals(pair.getNameAsString())) {
                    return stringValue(pair.getValue());
                }
            }
        }
        return null;
    }

    // 作用：读取注解的字符串数组成员；逻辑：数组展开后逐个转字符串，单值则包装成单元素列表。
    private static List<String> annotationStrings(AnnotationExpr annotation, String memberName) {
        Expression value = null;
        if (annotation instanceof SingleMemberAnnotationExpr single
                && "value".equals(memberName)) {
            value = single.getMemberValue();
        } else if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if (memberName.equals(pair.getNameAsString())) {
                    value = pair.getValue();
                    break;
                }
            }
        }
        if (value instanceof ArrayInitializerExpr array) {
            return array.getValues().stream()
                    .map(SourceTypeCatalog::stringValue)
                    .filter(item -> item != null && !item.isBlank())
                    .toList();
        }
        String single = stringValue(value);
        return single == null || single.isBlank() ? List.of() : List.of(single);
    }

    // 作用：把字面量表达式转为字符串；逻辑：支持字符串与布尔字面量，其他类型返回空。
    private static String stringValue(Expression expression) {
        if (expression instanceof StringLiteralExpr literal) {
            return literal.asString();
        }
        if (expression instanceof com.github.javaparser.ast.expr.BooleanLiteralExpr bool) {
            return Boolean.toString(bool.getValue());
        }
        return null;
    }

    // 作用：比较构造器形参与实参类型；逻辑：逐项按全名、简单名和原始/包装类型兼容性判断。
    private boolean parametersMatch(List<String> declaredTypes, List<String> actualTypes) {
        if (declaredTypes.size() != actualTypes.size()) {
            return false;
        }
        for (int index = 0; index < declaredTypes.size(); index++) {
            String declared = JavaTypeNameResolver.normalize(declaredTypes.get(index));
            String actual = JavaTypeNameResolver.normalize(actualTypes.get(index));
            if (declared == null || actual == null) {
                return false;
            }
            if (!declared.equals(actual)
                    && !simpleType(declared).equals(simpleType(actual))
                    && !wrapperMatches(declared, actual)) {
                return false;
            }
        }
        return true;
    }

    // 作用：提取类型简单名；逻辑：截取最后一个点号后的名称。
    private String simpleType(String type) {
        int separator = type.lastIndexOf('.');
        return separator < 0 ? type : type.substring(separator + 1);
    }

    // 作用：判断原始类型与包装类型是否互相匹配；逻辑：双向检查八种原始类型与对应包装类的简单名。
    private boolean wrapperMatches(String declared, String actual) {
        return switch (declared) {
            case "int" -> "Integer".equals(simpleType(actual));
            case "long" -> "Long".equals(simpleType(actual));
            case "double" -> "Double".equals(simpleType(actual));
            case "float" -> "Float".equals(simpleType(actual));
            case "boolean" -> "Boolean".equals(simpleType(actual));
            case "char" -> "Character".equals(simpleType(actual));
            case "short" -> "Short".equals(simpleType(actual));
            case "byte" -> "Byte".equals(simpleType(actual));
            default -> false;
        } || switch (actual) {
            case "int" -> "Integer".equals(simpleType(declared));
            case "long" -> "Long".equals(simpleType(declared));
            case "double" -> "Double".equals(simpleType(declared));
            case "float" -> "Float".equals(simpleType(declared));
            case "boolean" -> "Boolean".equals(simpleType(declared));
            case "char" -> "Character".equals(simpleType(declared));
            case "short" -> "Short".equals(simpleType(declared));
            case "byte" -> "Byte".equals(simpleType(declared));
            default -> false;
        };
    }

    // 作用：从方法或构造器声明构建方法信息；逻辑：生成全名与符号 ID，并记录参数、返回类型、类型参数及方法体标记。
    private static MethodInfo methodInfo(
            String moduleId,
            String typeQname,
            CallableDeclaration<?> callable,
            java.util.function.Function<String, String> returnTypeResolver
    ) {
        List<String> parameterTypes = JavaSymbolIdentity.parameterTypes(callable);
        String qname = JavaSymbolIdentity.callableQname(typeQname, callable, parameterTypes);
        return new MethodInfo(
                qname,
                JavaSymbolIdentity.callableSymbolId(moduleId, qname),
                JavaSymbolIdentity.displayName(callable),
                parameterTypes,
                returnType(callable, returnTypeResolver),
                callable instanceof MethodDeclaration method
                        ? method.getType().asString()
                        : null,
                callable.getParameters().stream()
                        .map(parameter -> parameter.getType().asString())
                        .toList(),
                callable.getTypeParameters().stream()
                        .map(parameter -> parameter.getNameAsString())
                        .toList(),
                callable instanceof MethodDeclaration method && method.getBody().isPresent()
        );
    }

    // 作用：解析可执行体的返回类型；逻辑：仅方法有返回值，void 返回空，其他类型经字段类型解析器规范化。
    private static String returnType(
            CallableDeclaration<?> callable,
            java.util.function.Function<String, String> returnTypeResolver
    ) {
        if (callable instanceof MethodDeclaration method) {
            String raw = JavaTypeNameResolver.normalize(method.getType().asString());
            if (raw == null || "void".equals(raw)) {
                return null;
            }
            return returnTypeResolver.apply(raw);
        }
        return null;
    }

    // 作用：判断类型声明是否多态；逻辑：接口或抽象类视为多态，其他类型返回假。
    private static boolean isPolymorphic(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration classType) {
            return classType.isInterface() || classType.isAbstract();
        }
        return false;
    }

    // 作用：提取类型声明中的类型参数名；逻辑：分别处理类/接口与 record 声明。
    private static List<String> typeParameters(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            return declaration.getTypeParameters().stream()
                    .map(parameter -> parameter.getNameAsString())
                    .toList();
        }
        if (type instanceof RecordDeclaration declaration) {
            return declaration.getTypeParameters().stream()
                    .map(parameter -> parameter.getNameAsString())
                    .toList();
        }
        return List.of();
    }

    // 作用：解析类型的父类型全名；逻辑：按类、枚举、record 分别收集 extends 与 implements 声明。
    private static List<String> superTypeQnames(
            TypeDeclaration<?> type,
            CompilationUnit unit,
            String packageName,
            Map<String, String> simpleNames
    ) {
        List<String> result = new ArrayList<>();
        if (type instanceof ClassOrInterfaceDeclaration classType) {
            classType.getExtendedTypes().forEach(superType -> result.add(resolveTypeName(unit, packageName, simpleNames, superType.getNameAsString())));
            classType.getImplementedTypes().forEach(superType -> result.add(resolveTypeName(unit, packageName, simpleNames, superType.getNameAsString())));
        } else if (type instanceof EnumDeclaration enumType) {
            enumType.getImplementedTypes().forEach(superType -> result.add(resolveTypeName(unit, packageName, simpleNames, superType.getNameAsString())));
        } else if (type instanceof RecordDeclaration recordType) {
            recordType.getImplementedTypes().forEach(superType -> result.add(resolveTypeName(unit, packageName, simpleNames, superType.getNameAsString())));
        }
        return List.copyOf(result);
    }

    // 作用：解析保留泛型的父类型表达式；逻辑：按类型声明种类收集 extends 与 implements 的完整表达式。
    private static List<String> superTypeExpressions(
            TypeDeclaration<?> type,
            CompilationUnit unit,
            String packageName,
            Map<String, String> simpleNames
    ) {
        List<String> result = new ArrayList<>();
        if (type instanceof ClassOrInterfaceDeclaration classType) {
            classType.getExtendedTypes().forEach(superType -> result.add(
                    resolveTypeExpression(unit, packageName, simpleNames, superType.asString())
            ));
            classType.getImplementedTypes().forEach(superType -> result.add(
                    resolveTypeExpression(unit, packageName, simpleNames, superType.asString())
            ));
        } else if (type instanceof EnumDeclaration enumType) {
            enumType.getImplementedTypes().forEach(superType -> result.add(
                    resolveTypeExpression(unit, packageName, simpleNames, superType.asString())
            ));
        } else if (type instanceof RecordDeclaration recordType) {
            recordType.getImplementedTypes().forEach(superType -> result.add(
                    resolveTypeExpression(unit, packageName, simpleNames, superType.asString())
            ));
        }
        return List.copyOf(result);
    }

    // 作用：解析带泛型的类型表达式；逻辑：拆出基础类型解析全名后，再拼回原有泛型部分。
    private static String resolveTypeExpression(
            CompilationUnit unit,
            String packageName,
            Map<String, String> simpleNames,
            String expression
    ) {
        String value = expression == null ? "" : expression.trim();
        int generic = value.indexOf('<');
        String base = generic < 0 ? value : value.substring(0, generic).trim();
        String qname = resolveTypeName(unit, packageName, simpleNames, base);
        return generic < 0 ? qname : qname + value.substring(generic);
    }

    // 作用：在编译单元上下文中解析类型全名；逻辑：按全名、显式导入、同包与全局简单名索引依次匹配。
    private static String resolveTypeName(CompilationUnit unit, String packageName, Map<String, String> simpleNames, String simpleName) {
        if (simpleName.contains(".")) {
            return simpleName;
        }
        for (var importDeclaration : unit.getImports()) {
            if (!importDeclaration.isAsterisk() && !importDeclaration.isStatic()) {
                String qname = importDeclaration.getNameAsString();
                if (qname.endsWith("." + simpleName)) {
                    return qname;
                }
            }
        }
        String samePackage = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        if (simpleNames.containsValue(samePackage)) {
            return samePackage;
        }
        String global = simpleNames.get(simpleName);
        return global != null ? global : simpleName;
    }

    // 作用：解析字段声明的基础类型；逻辑：去掉泛型参数后按类型名解析规则转为全名。
    private static String resolveFieldType(CompilationUnit unit, String packageName, Map<String, String> simpleNames, String typeName) {
        String base = typeName;
        int generic = base.indexOf('<');
        if (generic >= 0) {
            base = base.substring(0, generic);
        }
        return resolveTypeName(unit, packageName, simpleNames, base.trim());
    }

    public record TypeInfo(
            String qname,
            String symbolId,
            List<String> superTypes,
            List<String> superTypeExpressions,
            List<MethodInfo> methods,
            boolean polymorphic,
            List<String> typeParameters,
            boolean enumType,
            boolean generatedMembers
    ) {
    }

    public record MethodInfo(
            String qname,
            String symbolId,
            String simpleName,
            List<String> parameterTypes,
            String returnType,
            String returnTypeExpression,
            List<String> parameterTypeExpressions,
            List<String> typeParameters,
            boolean hasBody
    ) {
    }

    private record RawConstructor(
            String typeQname,
            List<String> parameterNames,
            List<String> parameterTypes,
            Map<Integer, String> directFields,
            String delegateTypeQname,
            List<Integer> delegateArgumentIndexes
    ) {
    }

    private record GeneratedConstructors(
            Map<Integer, String> allArgsConstructor,
            boolean allArgs,
            Map<Integer, String> requiredArgsConstructor,
            boolean requiredArgs,
            boolean noArgs
    ) {
    }
}
