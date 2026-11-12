package io.fieldtracegraph.javaanalysis.mybatis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.Provenance;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.JavaSymbolIdentity;
import io.fieldtracegraph.javaanalysis.resolution.JavaTypeNameResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

// 作用：生成 MyBatis 语句相关的字段值流。
// 使用场景：把 Mapper 方法参数、SQL 占位符与结果字段连接起来。

/**
 * Bridges MyBatis mapper method field parameters to SQL bind variables.
 * Java parameter values are the source; SQL bind variables are explicit
 * external boundaries.
 *
 * <p>SQL edges reuse the value node already produced by the Java extractor
 * whenever the bind expression maps to a parameter or field that was analysed.
 * Only SQL-only paths get a synthetic field node, and that node is still
 * attached to its object root through the normal field-expansion graph.</p>
 */
public final class MyBatisFieldLineageExtractor {
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".field-trace-graph", ".idea", ".vscode", "target", "build", "out", "dist", "node_modules"
    );

    private final MyBatisMapperXmlParser xmlParser = new MyBatisMapperXmlParser();
    private final MyBatisParameterResolver parameterResolver = new MyBatisParameterResolver();

    // 作用：汇总 MyBatis 的字段血缘；逻辑：索引 Mapper 类型与命名空间，解析 Mapper XML 并逐条语句发射参数与结果边。
    public void extract(
            Path projectRoot,
            Map<SourceFileRecord, CompilationUnit> units,
            JavaAnalysisAccumulator accumulator
    ) {
        Map<String, UnitType> types = typesByQname(units);
        Map<String, UnitType> namespaceTypes = typesByNamespace(types);
        Map<String, ValueNodeRecord> existingNodes = existingNodes(accumulator.valueNodes());
        Set<String> emittedNodeIds = new HashSet<>();
        accumulator.valueNodes().forEach(node -> emittedNodeIds.add(node.valueId()));
        Set<String> emittedEdgeIds = new HashSet<>();
        for (Path xml : mapperFiles(projectRoot)) {
            MyBatisMapperDefinition definition = xmlParser.parse(
                    projectRoot,
                    xml,
                    accumulator::addDiagnostic
            );
            if (definition == null) {
                continue;
            }
            UnitType mapper = types.get(definition.namespace());
            if (mapper == null) {
                mapper = namespaceTypes.get(definition.namespace());
            }
            if (mapper == null) {
                continue;
            }
            for (MyBatisMapperDefinition.Statement statement : definition.statements()) {
                MethodDeclaration method = method(mapper.type(), statement);
                if (method == null) {
                    continue;
                }
                emitStatement(
                        definition,
                        statement,
                        mapper,
                        method,
                        accumulator,
                        existingNodes,
                        emittedNodeIds,
                        emittedEdgeIds
                );
            }
        }
    }

    // 作用：发射单条 Mapper 语句的入参与结果映射；逻辑：解析占位符到 Java 参数路径，登记 SQL 边界节点与数据边。
    private void emitStatement(
            MyBatisMapperDefinition definition,
            MyBatisMapperDefinition.Statement statement,
            UnitType mapper,
            MethodDeclaration method,
            JavaAnalysisAccumulator accumulator,
            Map<String, ValueNodeRecord> existingNodes,
            Set<String> emittedNodeIds,
            Set<String> emittedEdgeIds
    ) {
        String methodQname = JavaSymbolIdentity.callableQname(
                mapper.typeQname(),
                method,
                JavaSymbolIdentity.parameterTypes(method)
        );
        String methodSymbolId = JavaSymbolIdentity.callableSymbolId(mapper.source().moduleId(), methodQname);
        int mapperLine = method.getBegin().map(position -> position.line).orElse(1);
        int ordinal = 0;
        for (MyBatisMapperDefinition.ParameterReference reference : statement.parameters()) {
            String valuePath = parameterResolver.valuePath(
                    reference.expression(),
                    method.getParameters(),
                    statement.foreachAliases(),
                    statement.bindAliases()
            );
            if (valuePath == null) {
                continue;
            }
            ValueNodeRecord source = fieldNode(
                    mapper,
                    definition,
                    reference,
                    methodSymbolId,
                    valuePath,
                    ordinal,
                    existingNodes
            );
            ValueNodeRecord target = sqlNode(
                    mapper,
                    definition,
                    statement,
                    reference,
                    methodSymbolId,
                    ordinal
            );
            addNodeOnce(accumulator, emittedNodeIds, source);
            addNodeOnce(accumulator, emittedNodeIds, target);
            String operation = definition.dialect() + " " + definition.relativePath() + ":"
                    + reference.line() + ":" + reference.columnStart()
                    + " #{" + reference.expression() + "}";
            String evidenceId = Ids.sha256(
                    methodSymbolId,
                    source.valueId(),
                    target.valueId(),
                    "SQL_PARAMETER",
                    definition.relativePath(),
                    String.valueOf(reference.line()),
                    String.valueOf(reference.columnStart())
            );
            String edgeId = Ids.sha256(evidenceId, String.valueOf(ordinal));
            if (!emittedEdgeIds.add(edgeId)) {
                continue;
            }
            accumulator.addDataEdge(new DataEdgeRecord(
                    edgeId,
                    methodSymbolId,
                    source.valueId(),
                    target.valueId(),
                    "SQL_PARAMETER",
                    operation,
                    reference.dynamic() ? "CANDIDATE" : "EXACT_STATIC",
                    evidenceId,
                    null,
                    null,
                    mapper.source().fileId(),
                    mapperLine,
                    method.getBegin().map(position -> position.column).orElse(1),
                    method.getEnd().map(position -> position.line).orElse(mapperLine),
                    method.getEnd().map(position -> position.column).orElse(1),
                    ordinal++,
                    Provenance.FRAMEWORK_ADAPTER.name()
            ));
        }
        for (MyBatisMapperDefinition.ResultMapping mapping : statement.resultMappings()) {
            MyBatisMapperDefinition.ParameterReference reference =
                    new MyBatisMapperDefinition.ParameterReference(
                            mapping.column(),
                            mapping.line(),
                            mapping.columnStart(),
                            mapping.line(),
                            mapping.columnStart() + mapping.column().length()
                    );
            ValueNodeRecord source = sqlResultNode(
                    mapper,
                    definition,
                    statement,
                    mapping,
                    methodSymbolId,
                    ordinal
            );
            ValueNodeRecord target = fieldNode(
                    mapper,
                    definition,
                    reference,
                    methodSymbolId,
                    "RETURN." + mapping.property(),
                    ordinal,
                    existingNodes
            );
            addNodeOnce(accumulator, emittedNodeIds, source);
            addNodeOnce(accumulator, emittedNodeIds, target);
            String operation = definition.dialect() + " " + definition.relativePath() + ":"
                    + mapping.line() + ":" + mapping.columnStart()
                    + " result " + mapping.column() + " -> " + mapping.property()
                    + (mapping.inferred() ? " (resultType auto mapping)" : "");
            String evidenceId = Ids.sha256(
                    methodSymbolId,
                    source.valueId(),
                    target.valueId(),
                    "ASSIGNED_FROM",
                    definition.relativePath(),
                    String.valueOf(mapping.line()),
                    String.valueOf(mapping.columnStart())
            );
            String edgeId = Ids.sha256(evidenceId, "result", String.valueOf(ordinal));
            if (emittedEdgeIds.add(edgeId)) {
                accumulator.addDataEdge(new DataEdgeRecord(
                        edgeId,
                        methodSymbolId,
                        source.valueId(),
                        target.valueId(),
                        "ASSIGNED_FROM",
                        operation,
                        mapping.inferred() ? "CANDIDATE" : "EXACT_STATIC",
                        evidenceId,
                        null,
                        null,
                        mapper.source().fileId(),
                        mapping.line(),
                        mapping.columnStart(),
                        mapping.line(),
                        mapping.columnStart() + mapping.column().length(),
                        ordinal++,
                        Provenance.FRAMEWORK_ADAPTER.name()
                ));
            }
        }
    }

    // 作用：去重追加值节点；逻辑：仅当值节点 ID 首次出现时写入累加器。
    private void addNodeOnce(
            JavaAnalysisAccumulator accumulator,
            Set<String> emittedNodeIds,
            ValueNodeRecord node
    ) {
        if (emittedNodeIds.add(node.valueId())) {
            accumulator.addValueNode(node);
        }
    }

    // 作用：取得参数路径对应的字段节点；逻辑：优先复用已有节点，缺失时按根对象与字段路径创建合成字段节点。
    private ValueNodeRecord fieldNode(
            UnitType mapper,
            MyBatisMapperDefinition definition,
            MyBatisMapperDefinition.ParameterReference reference,
            String methodSymbolId,
            String valuePath,
            int ordinal,
            Map<String, ValueNodeRecord> existingNodes
    ) {
        ValueNodeRecord existing = existingNodes.get(nodeKey(methodSymbolId, valuePath));
        if (existing != null) {
            return existing;
        }
        int dot = valuePath.indexOf('.');
        String root = dot < 0 ? valuePath : valuePath.substring(0, dot);
        List<String> path = dot < 0 ? List.of() : List.of(valuePath.substring(dot + 1).split("\\."));
        return new ValueNodeRecord(
                Ids.sha256(methodSymbolId, "mybatis-field", valuePath),
                methodSymbolId,
                mapper.source().fileId(),
                "FIELD",
                valuePath,
                root,
                path,
                9_000_000 + ordinal,
                ordinal,
                null,
                1,
                1,
                1,
                1,
                definition.relativePath(),
                reference.line(),
                reference.columnStart(),
                reference.lineEnd(),
                reference.columnEnd()
        );
    }

    // 作用：创建 SQL 占位符边界节点；逻辑：以命名空间、语句 ID 与表达式生成稳定标识和外部边界值。
    private ValueNodeRecord sqlNode(
            UnitType mapper,
            MyBatisMapperDefinition definition,
            MyBatisMapperDefinition.Statement statement,
            MyBatisMapperDefinition.ParameterReference reference,
            String methodSymbolId,
            int ordinal
    ) {
        String value = definition.namespace() + "#" + statement.id()
                + "." + reference.expression();
        return new ValueNodeRecord(
                Ids.sha256(methodSymbolId, "mybatis-sql", value, String.valueOf(reference.line()),
                        String.valueOf(reference.columnStart())),
                methodSymbolId,
                mapper.source().fileId(),
                "EXTERNAL_BOUNDARY",
                value,
                value,
                List.of(),
                9_500_000 + ordinal,
                ordinal,
                null,
                1,
                1,
                1,
                1,
                definition.relativePath(),
                reference.line(),
                reference.columnStart(),
                reference.lineEnd(),
                reference.columnEnd()
        );
    }

    // 作用：创建 SQL 结果列边界节点；逻辑：以语句 ID 与列名生成稳定标识和外部边界值。
    private ValueNodeRecord sqlResultNode(
            UnitType mapper,
            MyBatisMapperDefinition definition,
            MyBatisMapperDefinition.Statement statement,
            MyBatisMapperDefinition.ResultMapping mapping,
            String methodSymbolId,
            int ordinal
    ) {
        String value = definition.namespace() + "#" + statement.id()
                + ".result." + mapping.column();
        return new ValueNodeRecord(
                Ids.sha256(methodSymbolId, "mybatis-result", value,
                        String.valueOf(mapping.line()),
                        String.valueOf(mapping.columnStart())),
                methodSymbolId,
                mapper.source().fileId(),
                "EXTERNAL_BOUNDARY",
                value,
                value,
                List.of(),
                9_700_000 + ordinal,
                ordinal,
                null,
                1,
                1,
                1,
                1,
                definition.relativePath(),
                mapping.line(),
                mapping.columnStart(),
                mapping.line(),
                mapping.columnStart() + mapping.column().length()
        );
    }

    // 作用：按作用域与值建立已有节点索引；逻辑：过滤空值后按源码位置排序，保留位置最靠前的节点。
    private Map<String, ValueNodeRecord> existingNodes(List<ValueNodeRecord> nodes) {
        Map<String, ValueNodeRecord> result = new LinkedHashMap<>();
        nodes.stream()
                .filter(node -> node.canonicalValue() != null)
                .sorted(Comparator
                        .comparingInt(ValueNodeRecord::lineStart)
                        .thenComparingInt(ValueNodeRecord::columnStart)
                        .thenComparingInt(ValueNodeRecord::definitionOrdinal))
                .forEach(node -> result.put(
                        nodeKey(node.scopeSymbolId(), node.canonicalValue()),
                        node
                ));
        return result;
    }

    // 作用：生成节点索引键；逻辑：用作用域符号 ID 与规范值拼接成唯一键。
    private String nodeKey(String scopeSymbolId, String canonicalValue) {
        return scopeSymbolId + "\u001f" + canonicalValue;
    }

    // 作用：定位语句对应的 Mapper 方法；逻辑：按语句 ID 查方法，重载时再用 parameterType 的单参数类型消歧。
    private MethodDeclaration method(
            TypeDeclaration<?> type,
            MyBatisMapperDefinition.Statement statement
    ) {
        List<MethodDeclaration> matches = type.getMethodsByName(statement.id());
        if (matches.size() <= 1) {
            return matches.isEmpty() ? null : matches.get(0);
        }
        String declaredType = JavaTypeNameResolver.normalize(statement.parameterType());
        if (declaredType == null || declaredType.isBlank()) {
            return null;
        }
        String declaredSimple = simpleName(declaredType);
        List<MethodDeclaration> typed = matches.stream()
                .filter(method -> method.getParameters().size() == 1)
                .filter(method -> declaredSimple.equals(simpleName(JavaTypeNameResolver.normalize(
                        method.getParameter(0).getType().asString()
                ))))
                .toList();
        return typed.size() == 1 ? typed.get(0) : null;
    }

    // 作用：提取类型或注解简单名；逻辑：截取最后一个点号后的内容，空值返回空串。
    private String simpleName(String typeName) {
        if (typeName == null) {
            return "";
        }
        int dot = typeName.lastIndexOf('.');
        return dot < 0 ? typeName : typeName.substring(dot + 1);
    }

    // 作用：按全限定名索引源码类型；逻辑：遍历所有类型声明，以首次出现为准登记类型、所在文件与 AST 节点。
    private Map<String, UnitType> typesByQname(Map<SourceFileRecord, CompilationUnit> units) {
        Map<String, UnitType> result = new LinkedHashMap<>();
        for (var entry : units.entrySet()) {
            for (TypeDeclaration<?> type : entry.getValue().findAll(TypeDeclaration.class)) {
                String qname = type.getFullyQualifiedName().orElse(type.getNameAsString());
                result.putIfAbsent(qname, new UnitType(entry.getKey(), qname, type));
            }
        }
        return Map.copyOf(result);
    }

    // 作用：按 @Dao 注解值索引 Mapper 类型；逻辑：读取注解命名空间，并以首次登记为准建立映射。
    private Map<String, UnitType> typesByNamespace(Map<String, UnitType> types) {
        Map<String, UnitType> result = new LinkedHashMap<>();
        for (UnitType type : types.values()) {
            for (AnnotationExpr annotation : type.type().getAnnotations()) {
                if (!"Dao".equals(simpleName(annotation.getNameAsString()))) {
                    continue;
                }
                String namespace = annotationString(annotation);
                if (namespace != null && !namespace.isBlank()) {
                    result.putIfAbsent(namespace, type);
                }
            }
        }
        return Map.copyOf(result);
    }

    // 作用：读取注解中的字符串值；逻辑：支持单值注解与普通注解的 value 属性。
    private String annotationString(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            return stringValue(single.getMemberValue());
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                if ("value".equals(pair.getNameAsString())) {
                    return stringValue(pair.getValue());
                }
            }
        }
        return null;
    }

    // 作用：把表达式转换为字符串常量；逻辑：仅接受字符串字面量，其他形式返回空。
    private String stringValue(com.github.javaparser.ast.expr.Expression expression) {
        return expression instanceof StringLiteralExpr literal ? literal.asString() : null;
    }

    // 作用：查找项目中的 Mapper XML；逻辑：遍历 XML 文件，排除构建与版本控制目录，排序后限制数量。
    private List<Path> mapperFiles(Path projectRoot) {
        try (Stream<Path> stream = Files.walk(projectRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .filter(path -> !excluded(projectRoot, path))
                    .sorted()
                    .limit(2_000)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // 作用：判断路径是否位于排除目录；逻辑：检查相对路径的每一段是否命中排除集合。
    private boolean excluded(Path root, Path path) {
        for (Path segment : root.relativize(path)) {
            if (EXCLUDED_DIRECTORIES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private record UnitType(SourceFileRecord source, String typeQname, TypeDeclaration<?> type) {
    }
}
