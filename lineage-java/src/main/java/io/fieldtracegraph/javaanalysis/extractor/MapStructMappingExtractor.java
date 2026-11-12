package io.fieldtracegraph.javaanalysis.extractor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.MethodSummaryRecord;
import io.fieldtracegraph.core.model.Provenance;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.resolution.JavaTypeNameResolver;
import io.fieldtracegraph.javaanalysis.resolution.SourceTypeCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：提取 MapStruct 映射关系。
// 使用场景：@Mapping 与 qualifiedByName 等显式字段映射。

/**
 * Adds a deliberately conservative approximation for MapStruct mapper
 * interfaces. Generated implementations are usually not part of the analysed
 * source set, so the source annotations are the only evidence available.
 * Every edge produced here is CANDIDATE and never claims exact runtime
 * mapping semantics.
 */
public final class MapStructMappingExtractor {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ValueNodeCatalog nodeCatalog = new ValueNodeCatalog();

    // 作用：提取 MapStruct 映射；逻辑：识别无方法体的映射接口，按注解与同名规则生成候选字段复制边。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            TypeDeclaration<?> type,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            SourceTypeCatalog catalog
    ) {
        if (!(scope.callable() instanceof MethodDeclaration method)
                || type == null
                || !isMapStructMapper(type)
                || method.getBody().isPresent()
                || method.getType().isVoidType()
                || method.getParameters().isEmpty()) {
            return;
        }
        SourceTypeCatalog sourceCatalog = catalog;
        if (sourceCatalog == null) {
            return;
        }
        JavaTypeNameResolver resolver = new JavaTypeNameResolver(sourceCatalog);
        String returnType = resolver.resolve(
                method.findCompilationUnit().orElseThrow(),
                method.getType().asString()
        );
        String sourceType = resolver.resolve(
                method.findCompilationUnit().orElseThrow(),
                method.getParameter(0).getType().asString()
        );
        if (returnType == null || sourceType == null) {
            return;
        }
        String parameterName = method.getParameter(0).getNameAsString();
        ValueNodeRecord parameter = nodes.get(parameterName);
        if (parameter == null) {
            return;
        }
        ValueNodeRecord returnValue = nodeCatalog.ensure(
                source, scope, nodes, accumulator, "RETURN", "RETURN", method
        );
        Set<String> targetFields = sourceCatalog.fieldNames(returnType);
        Set<String> sourceFields = sourceCatalog.fieldNames(sourceType);
        List<MappingRule> rules = mappingRules(method);
        Set<String> explicitTargets = new LinkedHashSet<>();
        int ordinal = 0;
        for (MappingRule rule : rules) {
            if (rule.target() == null || rule.target().isBlank() || rule.ignore()) {
                if (rule.target() != null) {
                    explicitTargets.add(rule.target());
                }
                continue;
            }
            explicitTargets.add(rule.target());
            if (!targetFields.contains(rule.target())) {
                continue;
            }
            String sourcePath = rule.source();
            if (sourcePath == null || sourcePath.isBlank()) {
                sourcePath = rule.target();
            }
            String sourceField = sourceCatalog.fieldForSerializedName(sourceType, sourcePath);
            if (sourceField == null) {
                sourceField = sourcePath;
            }
            ordinal = addMappingEdge(
                    source,
                    scope,
                    nodes,
                    accumulator,
                    parameterName + "." + sourceField,
                    "RETURN." + rule.target(),
                    method,
                    rule,
                    sourceField,
                    rule.target(),
                    ordinal
            );
        }
        for (String targetField : targetFields) {
            if (explicitTargets.contains(targetField)
                    || sourceCatalog.isSerializationIgnored(returnType, targetField)) {
                continue;
            }
            String sourceField = sourceCatalog.fieldForSerializedName(sourceType, targetField);
            if (sourceField == null && sourceFields.contains(targetField)) {
                sourceField = targetField;
            }
            if (sourceField == null) {
                continue;
            }
            ordinal = addMappingEdge(
                    source,
                    scope,
                    nodes,
                    accumulator,
                    parameterName + "." + sourceField,
                    "RETURN." + targetField,
                    method,
                    null,
                    sourceField,
                    targetField,
                    ordinal
            );
        }
        addSummary(source, scope, method, nodes, accumulator, returnValue);
    }

    // 作用：写入映射边；逻辑：建立来源与目标字段节点，以框架适配来源和候选级别追加复制边。
    private int addMappingEdge(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            String sourceKey,
            String targetKey,
            MethodDeclaration method,
            MappingRule rule,
            String sourceField,
            String targetField,
            int ordinal
    ) {
        ValueNodeRecord origin = nodeCatalog.ensure(
                source, scope, nodes, accumulator, sourceKey, "FIELD", method
        );
        ValueNodeRecord target = nodeCatalog.ensure(
                source, scope, nodes, accumulator, targetKey, "FIELD", method
        );
        int line = method.getBegin().map(position -> position.line).orElse(1);
        int column = method.getBegin().map(position -> position.column).orElse(1);
        String operation = rule == null
                ? "MapStruct default mapping " + sourceField + " -> " + targetField
                : "MapStruct @Mapping(" + rule.expression() + ") "
                + sourceField + " -> " + targetField;
        String evidenceId = Ids.sha256(
                scope.symbolId(),
                origin.valueId(),
                target.valueId(),
                "COPY_ALL",
                "mapstruct",
                String.valueOf(line),
                String.valueOf(column)
        );
        accumulator.addDataEdge(new DataEdgeRecord(
                Ids.sha256(evidenceId, "mapstruct", String.valueOf(ordinal)),
                scope.symbolId(),
                origin.valueId(),
                target.valueId(),
                "COPY_ALL",
                operation,
                "CANDIDATE",
                evidenceId,
                null,
                null,
                source.fileId(),
                line,
                column,
                method.getEnd().map(position -> position.line).orElse(line),
                method.getEnd().map(position -> position.column).orElse(column),
                ordinal,
                Provenance.FRAMEWORK_ADAPTER.name()
        ));
        return ordinal + 1;
    }

    // 作用：登记映射方法摘要；逻辑：汇总参数与返回值节点，固定写入一条映射摘要。
    private void addSummary(
            SourceFileRecord source,
            CallableScope scope,
            MethodDeclaration method,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            ValueNodeRecord returnValue
    ) {
        List<Map<String, String>> parameters = new ArrayList<>();
        for (var parameter : method.getParameters()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", parameter.getNameAsString());
            entry.put("type", parameter.getType().asString());
            ValueNodeRecord node = nodes.get(parameter.getNameAsString());
            if (node != null) {
                entry.put("value_id", node.valueId());
            }
            parameters.add(entry);
        }
        accumulator.addMethodSummary(new MethodSummaryRecord(
                Ids.sha256(scope.symbolId(), "summary", "mapstruct"),
                scope.symbolId(),
                source.fileId(),
                json(parameters),
                returnValue.valueId(),
                1,
                null,
                "[]",
                List.of(),
                method.getBegin().map(position -> position.line).orElse(1),
                method.getBegin().map(position -> position.column).orElse(1),
                method.getEnd().map(position -> position.line).orElse(1),
                method.getEnd().map(position -> position.column).orElse(1)
        ));
    }

    // 作用：解析映射注解；逻辑：读取 target、source、ignore 与表达式等属性，组装映射规则列表。
    private List<MappingRule> mappingRules(MethodDeclaration method) {
        List<MappingRule> rules = new ArrayList<>();
        for (AnnotationExpr annotation : method.getAnnotations()) {
            if (!"Mapping".equals(simpleName(annotation.getNameAsString()))
                    || !(annotation instanceof NormalAnnotationExpr normal)) {
                continue;
            }
            Map<String, Expression> values = new LinkedHashMap<>();
            for (MemberValuePair pair : normal.getPairs()) {
                values.put(pair.getNameAsString(), pair.getValue());
            }
            String target = string(values.get("target"));
            String source = string(values.get("source"));
            boolean ignore = values.get("ignore") instanceof BooleanLiteralExpr bool
                    && bool.getValue();
            List<String> details = new ArrayList<>();
            for (String key : List.of("expression", "constant", "qualifiedByName", "qualifiedBy")) {
                if (values.containsKey(key)) {
                    details.add(key + "=" + values.get(key));
                }
            }
            rules.add(new MappingRule(target, source, ignore, String.join(",", details)));
        }
        return List.copyOf(rules);
    }

    // 作用：判断是否为 MapStruct 映射器；逻辑：检查 Mapper 注解的全限定名或导入来源。
    private boolean isMapStructMapper(TypeDeclaration<?> type) {
        for (AnnotationExpr annotation : type.getAnnotations()) {
            String name = annotation.getNameAsString();
            if ("Mapper".equals(simpleName(name))) {
                if (name.contains(".")) {
                    return name.startsWith("org.mapstruct.");
                }
                return hasImport(type, "org.mapstruct.Mapper")
                        || hasImport(type, "org.mapstruct.*");
            }
        }
        return false;
    }

    // 作用：检查类型导入；逻辑：匹配精确导入或通配导入的包前缀。
    private boolean hasImport(TypeDeclaration<?> type, String importedName) {
        return type.findCompilationUnit()
                .map(unit -> unit.getImports().stream()
                        .anyMatch(importDeclaration -> importedName.equals(
                                importDeclaration.getNameAsString()
                        ) || importedName.endsWith(".*")
                                && importDeclaration.getNameAsString().startsWith(
                                importedName.substring(0, importedName.length() - 1)
                        )))
                .orElse(false);
    }

    // 作用：取简单名称；逻辑：截取最后一个点号后的部分。
    private String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    // 作用：读取字符串字面量；逻辑：非字符串表达式返回 null。
    private String string(Expression expression) {
        return expression instanceof StringLiteralExpr literal ? literal.asString() : null;
    }

    // 作用：序列化摘要字段；逻辑：调用对象映射器，失败时抛出状态异常。
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize MapStruct summary", e);
        }
    }

    private record MappingRule(
            String target,
            String source,
            boolean ignore,
            String expression
    ) {
    }
}
