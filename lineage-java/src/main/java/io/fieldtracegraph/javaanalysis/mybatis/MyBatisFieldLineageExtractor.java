package io.fieldtracegraph.javaanalysis.mybatis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.NameExpr;
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
import java.util.regex.Pattern;
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
    /**
     * iBatis calls take the statement id as the first argument and the
     * parameter object as the second; the XML placeholder names are properties
     * of that object, not parameters of the Java mapper method.
     */
    private static final Set<String> IBATIS_CALL_OPERATIONS = Set.of(
            "insert", "update", "delete", "queryForObject", "queryForList",
            "queryForMap", "queryForPaginatedList", "queryWithRowHandler"
    );
    private static final Pattern PROPERTY_PATH =
            Pattern.compile("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*");

    private final MyBatisMapperXmlParser xmlParser = new MyBatisMapperXmlParser();
    private final MyBatisParameterResolver parameterResolver = new MyBatisParameterResolver();

    // 作用：只读取 Mapper XML 的命名空间；逻辑：供索引器提前判断哪些源码 AST 需要保留到 MyBatis 阶段。
    public Set<String> mapperNamespaces(Path projectRoot) {
        Set<String> namespaces = new HashSet<>();
        for (Path xml : mapperFiles(projectRoot)) {
            MyBatisMapperDefinition definition = xmlParser.parse(
                    projectRoot, xml, diagnostic -> { }
            );
            if (definition != null && definition.namespace() != null) {
                namespaces.add(definition.namespace());
            }
        }
        return namespaces;
    }

    /**
     * A source-located iBatis call. Collecting these while the file's AST is
     * still alive lets the indexer release each compilation unit right after
     * extraction instead of retaining every AST until the MyBatis phase.
     */
    public record IbatisCallSite(
            SourceFileRecord source,
            String symbolId,
            String statementId,
            String rootPath,
            int line,
            int column,
            int endLine,
            int endColumn
    ) {
    }

    // 作用：收集文件内的 iBatis 调用点；逻辑：只保留之后桥接所需的轻量字段，不持有 AST。
    public void collectIbatisCalls(
            SourceFileRecord source,
            CompilationUnit unit,
            List<IbatisCallSite> out
    ) {
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            IbatisCallSite site = ibatisCallSite(source, call);
            if (site != null) {
                out.add(site);
            }
        }
    }

    // 作用：识别并解析一个 iBatis 调用点；逻辑：校验接收者、语句字面量与参数根路径后生成轻量记录。
    private IbatisCallSite ibatisCallSite(SourceFileRecord source, MethodCallExpr call) {
        if (!IBATIS_CALL_OPERATIONS.contains(call.getNameAsString())
                || call.getArguments().size() < 2
                || !(call.getArgument(0) instanceof StringLiteralExpr statementLiteral)) {
            return null;
        }
        String receiver = call.getScope().map(Expression::toString).orElse("");
        if (!receiver.endsWith("sqlMapClientTemplate")
                && !receiver.endsWith("sqlMapClient")) {
            return null;
        }
        String rootPath = valueRoot(call.getArgument(1));
        if (rootPath == null) {
            return null;
        }
        MethodDeclaration method = call.findAncestor(MethodDeclaration.class).orElse(null);
        TypeDeclaration<?> type = call.findAncestor(TypeDeclaration.class).orElse(null);
        if (method == null || type == null) {
            return null;
        }
        String typeQname = type.getFullyQualifiedName().orElse(type.getNameAsString());
        String qname = JavaSymbolIdentity.callableQname(
                typeQname,
                method,
                JavaSymbolIdentity.parameterTypes(method)
        );
        String symbolId = JavaSymbolIdentity.callableSymbolId(source.moduleId(), qname);
        int line = call.getBegin().map(position -> position.line).orElse(1);
        int column = call.getBegin().map(position -> position.column).orElse(1);
        int endLine = call.getEnd().map(position -> position.line).orElse(line);
        int endColumn = call.getEnd().map(position -> position.column).orElse(column);
        return new IbatisCallSite(
                source, symbolId, statementLiteral.asString(), rootPath,
                line, column, endLine, endColumn
        );
    }

    // 作用：汇总 MyBatis 的字段血缘；逻辑：索引 Mapper 类型与命名空间，解析 Mapper XML 并逐条语句发射参数与结果边。
    public void extract(
            Path projectRoot,
            Map<SourceFileRecord, CompilationUnit> units,
            JavaAnalysisAccumulator accumulator
    ) {
        extract(projectRoot, units, null, accumulator);
    }

    // 作用：汇总 MyBatis 的字段血缘；逻辑：可复用调用方已收集的 iBatis 调用点，避免长期持有全部 AST。
    public void extract(
            Path projectRoot,
            Map<SourceFileRecord, CompilationUnit> units,
            List<IbatisCallSite> ibatisCalls,
            JavaAnalysisAccumulator accumulator
    ) {
        Map<String, UnitType> types = typesByQname(units);
        Map<String, UnitType> namespaceTypes = typesByNamespace(types);
        Map<String, ValueNodeRecord> existingNodes = existingNodes(accumulator.valueNodes());
        Set<String> emittedNodeIds = new HashSet<>();
        accumulator.valueNodes().forEach(node -> emittedNodeIds.add(node.valueId()));
        Set<String> emittedEdgeIds = new HashSet<>();
        Map<String, StatementBinding> statements = new LinkedHashMap<>();
        for (Path xml : mapperFiles(projectRoot)) {
            MyBatisMapperDefinition definition = xmlParser.parse(
                    projectRoot,
                    xml,
                    accumulator::addDiagnostic
            );
            if (definition == null) {
                continue;
            }
            for (MyBatisMapperDefinition.Statement statement : definition.statements()) {
                StatementBinding binding = new StatementBinding(definition, statement);
                statements.putIfAbsent(statementKey(definition.namespace(), statement.id()), binding);
                statements.putIfAbsent(statement.id(), binding);
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
        if (ibatisCalls == null) {
            ibatisCalls = new java.util.ArrayList<>();
            for (var entry : units.entrySet()) {
                collectIbatisCalls(entry.getKey(), entry.getValue(), ibatisCalls);
            }
        }
        bridgeIbatisCalls(
                ibatisCalls,
                statements,
                existingNodes,
                emittedNodeIds,
                emittedEdgeIds,
                accumulator
        );
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
                    method,
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
                    method,
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
                    /*
                     * The dynamic flag describes the surrounding SQL fragment
                     * (an <if>/<foreach> wrapper), not the parameter binding.
                     * When the mapper takes a single POJO parameter, #{name}
                     * always resolves to that object's property, so the edge
                     * is exact even inside dynamic SQL. Keeping it CANDIDATE
                     * turned every conditional statement into PARTIAL.
                     */
                    !reference.dynamic() || singlePojoParameter(method)
                            ? "EXACT_STATIC"
                            : "CANDIDATE",
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
                    method,
                    ordinal
            );
            ValueNodeRecord target = fieldNode(
                    mapper,
                    definition,
                    reference,
                    methodSymbolId,
                    method,
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

    /**
     * A single POJO parameter is the MyBatis "parameter object": every
     * top-level {@code #{property}} reads that object's property. A single
     * Map/Collection/String parameter is a value or map key instead, so it
     * stays name-based and keeps the candidate marking.
     */
    // 作用：判断 Mapper 是否只有一个 POJO 参数；逻辑：排除集合/Map/标量与多参数。
    private boolean singlePojoParameter(MethodDeclaration method) {
        if (method.getParameters().size() != 1) {
            return false;
        }
        String type = method.getParameter(0).getType().asString();
        int generic = type.indexOf('<');
        String simple = (generic < 0 ? type : type.substring(0, generic)).trim();
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) {
            simple = simple.substring(dot + 1);
        }
        return !simple.endsWith("Map") && !simple.endsWith("Collection")
                && !simple.endsWith("List") && !simple.endsWith("Set")
                && !simple.endsWith("JSONObject") && !simple.endsWith("JSONArray")
                && !simple.equals("String") && !simple.equals("Object")
                && !simple.equals("int") && !simple.equals("long")
                && !simple.equals("Integer") && !simple.equals("Long");
    }

    /**
     * iBatis DAOs pass a literal statement id plus a parameter object, for
     * example {@code sqlMapClientTemplate.insert("ActivityAreaPros.insert",
     * vo)}. The XML statement is in the repository, so the relationship is
     * provable rather than a runtime guess: the placeholder properties are
     * read from the parameter object at that call site.
     */
    // 作用：桥接 iBatis 字符串语句调用；逻辑：按字面量语句 ID 找 XML 语句，把参数对象属性连到 SQL 边界。
    private void bridgeIbatisCalls(
            List<IbatisCallSite> calls,
            Map<String, StatementBinding> statements,
            Map<String, ValueNodeRecord> existingNodes,
            Set<String> emittedNodeIds,
            Set<String> emittedEdgeIds,
            JavaAnalysisAccumulator accumulator
    ) {
        if (statements.isEmpty() || calls == null) {
            return;
        }
        for (IbatisCallSite site : calls) {
            StatementBinding binding = statements.get(site.statementId());
            if (binding == null) {
                String id = site.statementId();
                int dot = id.lastIndexOf('.');
                if (dot > 0) {
                    binding = statements.get(id.substring(dot + 1));
                }
            }
            if (binding == null) {
                continue;
            }
            SourceFileRecord source = site.source();
            String rootPath = site.rootPath();
            String symbolId = site.symbolId();
            int callLine = site.line();
            int callColumn = site.column();
            int ordinal = 0;
            for (MyBatisMapperDefinition.ParameterReference reference
                    : binding.statement().parameters()) {
                String property = reference.expression();
                if (property == null || !PROPERTY_PATH.matcher(property).matches()) {
                    continue;
                }
                String valuePath = rootPath + "." + property;
                ValueNodeRecord sourceNode = existingNodes.get(nodeKey(symbolId, valuePath));
                if (sourceNode == null) {
                    sourceNode = ibatisFieldNode(
                            source, symbolId, valuePath, binding, reference,
                            callLine, callColumn, ordinal
                    );
                }
                ValueNodeRecord target = ibatisSqlNode(
                        source, symbolId, binding, reference, callLine, callColumn, ordinal
                );
                addNodeOnce(accumulator, emittedNodeIds, sourceNode);
                addNodeOnce(accumulator, emittedNodeIds, target);
                String operation = "iBatis " + binding.definition().relativePath() + ":"
                        + reference.line() + ":" + reference.columnStart()
                        + " #" + property + "#";
                String evidenceId = Ids.sha256(
                        symbolId,
                        sourceNode.valueId(),
                        target.valueId(),
                        "SQL_PARAMETER",
                        binding.definition().relativePath(),
                        String.valueOf(reference.line()),
                        String.valueOf(reference.columnStart())
                );
                String edgeId = Ids.sha256(evidenceId, "ibatis", String.valueOf(ordinal));
                if (emittedEdgeIds.add(edgeId)) {
                    accumulator.addDataEdge(new DataEdgeRecord(
                            edgeId,
                            symbolId,
                            sourceNode.valueId(),
                            target.valueId(),
                            "SQL_PARAMETER",
                            operation,
                            "EXACT_STATIC",
                            evidenceId,
                            null,
                            null,
                            source.fileId(),
                            callLine,
                            callColumn,
                            site.endLine(),
                            site.endColumn(),
                            ordinal++,
                            Provenance.FRAMEWORK_ADAPTER.name()
                    ));
                } else {
                    ordinal++;
                }
            }
        }
    }

    // 作用：取 iBatis 参数对象根路径；逻辑：仅接受变量或 this 字段访问，方法调用等动态表达式返回空。
    private String valueRoot(Expression expression) {
        Expression current = expression;
        if (current instanceof NameExpr name) {
            return name.getNameAsString();
        }
        if (current instanceof FieldAccessExpr field && field.getScope().isThisExpr()) {
            return "this." + field.getNameAsString();
        }
        return null;
    }

    // 作用：创建 iBatis 参数字段节点；逻辑：作用域用调用方方法，证据指向 XML 占位符。
    private ValueNodeRecord ibatisFieldNode(
            SourceFileRecord source,
            String symbolId,
            String valuePath,
            StatementBinding binding,
            MyBatisMapperDefinition.ParameterReference reference,
            int callLine,
            int callColumn,
            int ordinal
    ) {
        int dot = valuePath.indexOf('.');
        String root = dot < 0 ? valuePath : valuePath.substring(0, dot);
        List<String> path = dot < 0
                ? List.of()
                : List.of(valuePath.substring(dot + 1).split("\\."));
        return new ValueNodeRecord(
                Ids.sha256(symbolId, "ibatis-field", valuePath),
                symbolId,
                source.fileId(),
                "FIELD",
                valuePath,
                root,
                path,
                9_000_000 + ordinal,
                ordinal,
                null,
                callLine,
                callColumn,
                callLine,
                callColumn,
                binding.definition().relativePath(),
                reference.line(),
                reference.columnStart(),
                reference.lineEnd(),
                reference.columnEnd()
        );
    }

    // 作用：创建 iBatis SQL 占位符边界节点；逻辑：命名空间+语句 ID+属性构成稳定外部值。
    private ValueNodeRecord ibatisSqlNode(
            SourceFileRecord source,
            String symbolId,
            StatementBinding binding,
            MyBatisMapperDefinition.ParameterReference reference,
            int callLine,
            int callColumn,
            int ordinal
    ) {
        String value = binding.definition().namespace() + "#" + binding.statement().id()
                + "." + reference.expression();
        return new ValueNodeRecord(
                Ids.sha256(symbolId, "ibatis-sql", value,
                        String.valueOf(reference.line()),
                        String.valueOf(reference.columnStart())),
                symbolId,
                source.fileId(),
                "EXTERNAL_BOUNDARY",
                value,
                value,
                List.of(),
                9_600_000 + ordinal,
                ordinal,
                null,
                callLine,
                callColumn,
                callLine,
                callColumn,
                binding.definition().relativePath(),
                reference.line(),
                reference.columnStart(),
                reference.lineEnd(),
                reference.columnEnd()
        );
    }

    // 作用：生成语句索引键；逻辑：有命名空间时拼 namespace.id，否则只用 id。
    private String statementKey(String namespace, String id) {
        return namespace == null || namespace.isBlank() ? id : namespace + "." + id;
    }
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
            MethodDeclaration method,
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
                methodBeginLine(method),
                methodBeginColumn(method),
                methodEndLine(method),
                methodEndColumn(method),
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
            MethodDeclaration method,
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
                methodBeginLine(method),
                methodBeginColumn(method),
                methodEndLine(method),
                methodEndColumn(method),
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
            MethodDeclaration method,
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
                methodBeginLine(method),
                methodBeginColumn(method),
                methodEndLine(method),
                methodEndColumn(method),
                definition.relativePath(),
                mapping.line(),
                mapping.columnStart(),
                mapping.line(),
                mapping.columnStart() + mapping.column().length()
        );
    }

    /*
     * MyBatis value nodes live in the mapper interface scope while their
     * evidence is the XML statement. Anchoring them at line 1 pointed every
     * reader at the package declaration; the interface method is the closest
     * Java location for the node, and the XML position is kept as origin.
     */
    // 作用：取 Mapper 方法起始行；逻辑：缺少位置信息时退回第 1 行。
    private static int methodBeginLine(MethodDeclaration method) {
        return method.getBegin().map(position -> position.line).orElse(1);
    }

    // 作用：取 Mapper 方法起始列；逻辑：缺少位置信息时退回第 1 列。
    private static int methodBeginColumn(MethodDeclaration method) {
        return method.getBegin().map(position -> position.column).orElse(1);
    }

    // 作用：取 Mapper 方法结束行；逻辑：缺少位置信息时退回起始行。
    private static int methodEndLine(MethodDeclaration method) {
        return method.getEnd().map(position -> position.line).orElse(methodBeginLine(method));
    }

    // 作用：取 Mapper 方法结束列；逻辑：缺少位置信息时退回起始列。
    private static int methodEndColumn(MethodDeclaration method) {
        return method.getEnd().map(position -> position.column).orElse(methodBeginColumn(method));
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

    private record StatementBinding(
            MyBatisMapperDefinition definition,
            MyBatisMapperDefinition.Statement statement
    ) {
    }
}
