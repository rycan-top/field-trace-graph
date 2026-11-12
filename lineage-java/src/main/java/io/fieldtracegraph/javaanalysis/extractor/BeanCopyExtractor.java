package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.resolution.ExpressionTypeResolver;
import io.fieldtracegraph.javaanalysis.resolution.SourceTypeCatalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

// 作用：提取属性拷贝工具产生的字段映射。
// 使用场景：BeanUtils.copyProperties 等整对象拷贝。

/**
 * Recognizes whole-object copy contracts. A copy is intentionally represented
 * by one directed COPY_ALL edge; field-level paths are preserved by the query
 * layer's field propagation, while the source/target objects remain auditable.
 */
public final class BeanCopyExtractor {
    private final ValueNodeCatalog nodeCatalog = new ValueNodeCatalog();


    // 作用：提取直接与经本地辅助方法包装的属性拷贝；逻辑：先识别直接调用，再解析本地方法契约并映射实参位置。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            SourceTypeCatalog catalog
    ) {
        int statementOrdinal = 0;
        Set<String> emitted = new LinkedHashSet<>();
        for (MethodCallExpr call : scope.callable().findAll(MethodCallExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())) {
                continue;
            }
            CopyOperands direct = directCopyOperands(call, context);
            if (direct != null) {
                statementOrdinal = addCopy(source, scope, nodes, accumulator, context,
                        direct, call, statementOrdinal, emitted, catalog);
            }
        }
        for (MethodCallExpr call : scope.callable().findAll(MethodCallExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())
                    || directCopyOperands(call, context) != null) {
                continue;
            }
            MethodDeclaration helper = resolveLocalMethod(call, scope.callable());
            if (helper == null) {
                continue;
            }
            for (CopyContract contract : contracts(helper, new LinkedHashSet<>())) {
                if (contract.sourceParameter() >= call.getArguments().size()
                        || contract.targetParameter() >= call.getArguments().size()) {
                    continue;
                }
                statementOrdinal = addCopy(source, scope, nodes, accumulator, context,
                        new CopyOperands(
                                call.getArgument(contract.sourceParameter()),
                                call.getArgument(contract.targetParameter()),
                                Set.of(),
                                false,
                                "EXACT_STATIC"
                        ),
                        call, statementOrdinal, emitted, catalog);
            }
        }
    }

    // 作用：登记一次整对象拷贝；逻辑：校验并去重端点，按精度登记整对象边，再尝试补充字段级映射。
    private int addCopy(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            CopyOperands operands,
            Node anchor,
            int statementOrdinal,
            Set<String> emitted,
            SourceTypeCatalog catalog
    ) {
        Expression sourceExpression = operands.source();
        Expression targetExpression = operands.target();
        String sourceKey = context.key(sourceExpression);
        String targetKey = context.key(targetExpression);
        if (sourceKey == null || targetKey == null || sourceKey.isBlank() || targetKey.isBlank()
                || sourceKey.equals(targetKey)) {
            return statementOrdinal;
        }
        String marker = sourceKey + "\u0001" + targetKey + "\u0001"
                + anchor.getBegin().map(position -> position.line).orElse(1);
        if (!emitted.add(marker)) {
            return statementOrdinal;
        }
        ValueNodeRecord origin = nodeCatalog.ensure(
                source, scope, nodes, accumulator, sourceKey, "EXPRESSION", sourceExpression
        );
        ValueNodeRecord target = nodeCatalog.ensure(
                source, scope, nodes, accumulator, targetKey, "EXPRESSION", targetExpression
        );
        int line = anchor.getBegin().map(position -> position.line).orElse(1);
        int column = anchor.getBegin().map(position -> position.column).orElse(1);
        int ordinal = statementOrdinal;
        if (!operands.hasIgnoreSpecification()) {
            String evidenceId = Ids.sha256(scope.symbolId(), origin.valueId(), target.valueId(),
                    "COPY_ALL", String.valueOf(line), String.valueOf(column));
            accumulator.addDataEdge(new DataEdgeRecord(
                    Ids.sha256(scope.symbolId(), origin.valueId(), target.valueId(),
                            "COPY_ALL", String.valueOf(line), String.valueOf(column), String.valueOf(ordinal)),
                    scope.symbolId(),
                    origin.valueId(),
                    target.valueId(),
                    "COPY_ALL",
                    "bean copy",
                    operands.precision(),
                    evidenceId,
                    null,
                    null,
                    source.fileId(),
                    line,
                    column,
                    anchor.getEnd().map(position -> position.line).orElse(line),
                    anchor.getEnd().map(position -> position.column).orElse(column),
                    ordinal++
            ));
        }
        return addMappedFieldCopies(source, scope, nodes, accumulator, context, catalog,
                sourceKey, targetKey, sourceExpression, targetExpression, anchor,
                operands.ignoredFields(), operands.precision(),
                ordinal, emitted);
    }

    // 作用：为可解析类型的拷贝补充字段级映射；逻辑：对照双方字段与别名，过滤忽略字段后逐字段登记数据边。
    private int addMappedFieldCopies(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            SourceTypeCatalog catalog,
            String sourceKey,
            String targetKey,
            Expression sourceExpression,
            Expression targetExpression,
            Node anchor,
            Set<String> ignoredFields,
            String precision,
            int statementOrdinal,
            Set<String> emitted
    ) {
        if (catalog == null) {
            return statementOrdinal;
        }
        ExpressionTypeResolver typeResolver = new ExpressionTypeResolver(catalog);
        String sourceType = typeResolver.resolve(sourceExpression, scope, context);
        String targetType = typeResolver.resolve(targetExpression, scope, context);
        if (sourceType == null || targetType == null) {
            return statementOrdinal;
        }
        Set<String> targetFields = catalog.fieldNames(targetType);
        Map<String, String> commonFields = new TreeMap<>();
        Set<String> sourceFields = catalog.fieldNames(sourceType);
        for (String targetField : targetFields) {
            String sourceField = matchingSourceField(
                    catalog, sourceType, sourceFields, targetType, targetField
            );
            if (sourceField != null) {
                commonFields.put(targetField, sourceField);
            }
        }
        int ordinal = statementOrdinal;
        for (Map.Entry<String, String> match : commonFields.entrySet()) {
            String targetField = match.getKey();
            String sourceField = match.getValue();
            if (isIgnored(catalog, sourceType, sourceField, ignoredFields)
                    || isIgnored(catalog, targetType, targetField, ignoredFields)) {
                continue;
            }
            String fieldSourceKey = sourceKey + "." + sourceField;
            String fieldTargetKey = targetKey + "." + targetField;
            String marker = fieldSourceKey + "\u0001" + fieldTargetKey + "\u0001"
                    + anchor.getBegin().map(position -> position.line).orElse(1);
            if (!emitted.add(marker)) {
                continue;
            }
            ValueNodeRecord origin = nodeCatalog.ensure(
                    source, scope, nodes, accumulator, fieldSourceKey, "FIELD", sourceExpression
            );
            ValueNodeRecord target = nodeCatalog.ensure(
                    source, scope, nodes, accumulator, fieldTargetKey, "FIELD", targetExpression
            );
            int line = anchor.getBegin().map(position -> position.line).orElse(1);
            int column = anchor.getBegin().map(position -> position.column).orElse(1);
            String evidenceId = Ids.sha256(scope.symbolId(), origin.valueId(), target.valueId(),
                    "COPY_ALL", "bean property", String.valueOf(line), String.valueOf(column));
            accumulator.addDataEdge(new DataEdgeRecord(
                    Ids.sha256(scope.symbolId(), origin.valueId(), target.valueId(),
                            "COPY_ALL", "bean property", String.valueOf(line), String.valueOf(column),
                            String.valueOf(ordinal)),
                    scope.symbolId(),
                    origin.valueId(),
                    target.valueId(),
                    "COPY_ALL",
                    "bean copy property " + sourceField + " -> " + targetField,
                    precision,
                    evidenceId,
                    null,
                    null,
                    source.fileId(),
                    line,
                    column,
                    anchor.getEnd().map(position -> position.line).orElse(line),
                    anchor.getEnd().map(position -> position.column).orElse(column),
                    ordinal++
            ));
        }
        return ordinal;
    }

    // 作用：寻找目标字段对应的源字段；逻辑：优先同名，再按序列化名和双方别名匹配。
    private String matchingSourceField(
            SourceTypeCatalog catalog,
            String sourceType,
            Set<String> sourceFields,
            String targetType,
            String targetField
    ) {
        if (sourceFields.contains(targetField)) {
            return targetField;
        }
        Set<String> targetAliases = catalog.fieldAliases(targetType, targetField);
        String bySourceAlias = catalog.fieldForSerializedName(sourceType, targetField);
        if (bySourceAlias != null && sourceFields.contains(bySourceAlias)) {
            return bySourceAlias;
        }
        for (String sourceField : sourceFields) {
            if (catalog.fieldAliases(sourceType, sourceField).contains(targetField)
                    || targetAliases.contains(sourceField)) {
                return sourceField;
            }
        }
        return null;
    }

    // 作用：判断字段是否被忽略；逻辑：同时检查字段本名及其类型别名是否出现在忽略集合中。
    private boolean isIgnored(
            SourceTypeCatalog catalog,
            String typeQname,
            String field,
            Set<String> ignoredFields
    ) {
        if (ignoredFields.contains(field)) {
            return true;
        }
        return catalog.fieldAliases(typeQname, field).stream().anyMatch(ignoredFields::contains);
    }

    // 作用：识别直接属性拷贝调用；逻辑：按 BeanUtils、Hutool、BeanCopier 等工具约定解析源、目标、忽略项与精度。
    private CopyOperands directCopyOperands(MethodCallExpr call, JavaValueFlowContext context) {
        String method = call.getNameAsString();
        String receiver = call.getScope().map(Object::toString).orElse("");
        boolean beanUtilsCopy = "copyProperties".equals(method)
                && call.getArguments().size() >= 2
                && (receiver.endsWith("BeanUtils")
                || receiver.endsWith("BeanUtils.")
                || hasStaticImport(call, "org.springframework.beans.BeanUtils", "copyProperties")
                || hasStaticImport(call, "org.apache.commons.beanutils.BeanUtils", "copyProperties"));
        boolean beanCopyValue = "copyPropertiesValue".equals(method)
                && call.getArguments().size() >= 2
                && receiver.toLowerCase(java.util.Locale.ROOT).contains("beancopy");
        boolean hutoolBeanCopy = "copyProperties".equals(method)
                && call.getArguments().size() >= 2
                && (isHutoolBeanUtil(call, receiver)
                || hasStaticImport(call, "cn.hutool.core.bean.BeanUtil", "copyProperties"));
        if (hutoolBeanCopy) {
            Set<String> ignoredFields = hutoolIgnoredFields(call);
            String precision = hutoolCopyPrecision(call, context);
            return new CopyOperands(
                    call.getArgument(0),
                    call.getArgument(1),
                    ignoredFields,
                    call.getArguments().size() >= 3,
                    precision
            );
        }
        if ("copyProperties".equals(method) && call.getArguments().size() >= 2
                && isLocalBeanUtilReceiver(call, receiver)) {
            SpringCopyArguments arguments = springCopyArguments(call, true);
            return new CopyOperands(
                    call.getArgument(0),
                    call.getArgument(1),
                    arguments.ignoredFields(),
                    arguments.hasIgnoreSpecification(),
                    arguments.precision()
            );
        }
        if (beanUtilsCopy || beanCopyValue) {
            boolean apache = receiver.contains("apache.commons")
                    || importsType(call, "org.apache.commons.beanutils.BeanUtils");
            if (apache) {
                return new CopyOperands(
                        call.getArgument(1),
                        call.getArgument(0),
                        Set.of(),
                        false,
                        "EXACT_STATIC"
                );
            }
            SpringCopyArguments arguments = springCopyArguments(call, false);
            return new CopyOperands(
                    call.getArgument(0),
                    call.getArgument(1),
                    arguments.ignoredFields(),
                    arguments.hasIgnoreSpecification(),
                    arguments.precision()
            );
        }
        if ("copy".equals(method) && call.getArguments().size() >= 2
                && (receiver.toLowerCase(java.util.Locale.ROOT).contains("beancopy")
                || receiver.toLowerCase(java.util.Locale.ROOT).contains("beancopier")
                || "BeanCopier".equals(context.simpleType(context.declaredType(receiver))))) {
            return new CopyOperands(call.getArgument(0), call.getArgument(1),
                    Set.of(), false, "EXACT_STATIC");
        }
        return null;
    }

    // 作用：解析 Spring 风格拷贝的忽略参数；逻辑：跳过 class 参数，收集可静态确定的忽略字段并判断参数是否完整。
    private SpringCopyArguments springCopyArguments(MethodCallExpr call, boolean wrapper) {
        int ignoreStart = 2;
        boolean incomplete = false;
        boolean hasIgnoreSpecification = call.getArguments().size() >= 3;
        if (call.getArguments().size() >= 3
                && isClassLiteral(call.getArgument(2))) {
            ignoreStart = 3;
            incomplete = true;
        }
        Set<String> ignoredFields = new LinkedHashSet<>();
        for (int index = ignoreStart; index < call.getArguments().size(); index++) {
            if (!collectIgnoredFields(call.getArgument(index), ignoredFields)) {
                incomplete = true;
            }
        }
        return new SpringCopyArguments(
                Set.copyOf(ignoredFields),
                hasIgnoreSpecification,
                wrapper || incomplete ? "CANDIDATE" : "EXACT_STATIC"
        );
    }

    // 作用：收集忽略字段表达式；逻辑：字符串直接加入，数组递归展开，其他形式标记为不可完整解析。
    private boolean collectIgnoredFields(Expression expression, Set<String> ignoredFields) {
        if (expression instanceof StringLiteralExpr literal) {
            ignoredFields.add(literal.asString());
            return true;
        }
        if (expression instanceof ArrayInitializerExpr array) {
            boolean complete = true;
            for (Expression value : array.getValues()) {
                complete &= collectIgnoredFields(value, ignoredFields);
            }
            return complete;
        }
        return false;
    }

    // 作用：判断表达式是否为类字面量；逻辑：兼容 ClassExpr 与源码文本以 .class 结尾的形式。
    private boolean isClassLiteral(Expression expression) {
        return expression instanceof ClassExpr
                || expression.toString().endsWith(".class");
    }

    // 作用：判断接收者是否为本地 BeanUtil；逻辑：按简单名、导入与静态导入识别，并排除 Hutool 同名工具。
    private boolean isLocalBeanUtilReceiver(MethodCallExpr call, String receiver) {
        String simple = simpleName(receiver);
        return "BeanUtil".equals(simple)
                && !isHutoolBeanUtil(call, receiver)
                && (receiver.endsWith(".BeanUtil")
                || importsTypeEndingWith(call, ".BeanUtil")
                || hasStaticImportEndingWith(call, ".BeanUtil", "copyProperties"));
    }

    // 作用：判断编译单元是否导入指定后缀的类型；逻辑：遍历普通导入并匹配完整类名后缀。
    private boolean importsTypeEndingWith(MethodCallExpr call, String suffix) {
        return call.findCompilationUnit()
                .map(unit -> unit.getImports().stream()
                        .anyMatch(importDeclaration -> !importDeclaration.isStatic()
                                && importDeclaration.getNameAsString().endsWith(suffix)))
                .orElse(false);
    }

    // 作用：判断是否存在指定类型方法的静态导入；逻辑：遍历静态导入并匹配“类型后缀.方法名”。
    private boolean hasStaticImportEndingWith(
            MethodCallExpr call,
            String typeSuffix,
            String methodName
    ) {
        return call.findCompilationUnit()
                .map(unit -> unit.getImports().stream()
                        .anyMatch(importDeclaration -> importDeclaration.isStatic()
                                && importDeclaration.getNameAsString().endsWith(
                                typeSuffix + "." + methodName)))
                .orElse(false);
    }

    // 作用：提取类型或表达式的简单名；逻辑：去掉最后一个点号之前的部分，空值返回空串。
    private String simpleName(String value) {
        String simple = value == null ? "" : value.trim();
        int dot = simple.lastIndexOf('.');
        return dot < 0 ? simple : simple.substring(dot + 1);
    }

    // 作用：判断调用是否来自 Hutool 的 BeanUtil；逻辑：比对接收者简单名并检查对应类型的导入。
    private boolean isHutoolBeanUtil(MethodCallExpr call, String receiver) {
        String simple = receiver;
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) {
            simple = simple.substring(dot + 1);
        }
        return ("BeanUtil".equals(simple)
                && importsType(call, "cn.hutool.core.bean.BeanUtil"))
                || receiver.equals("cn.hutool.core.bean.BeanUtil");
    }

    // 作用：提取 Hutool 拷贝的忽略字段；逻辑：读取第三个参数中的字符串或字符串数组。
    private Set<String> hutoolIgnoredFields(MethodCallExpr call) {
        if (call.getArguments().size() < 3) {
            return Set.of();
        }
        Expression options = call.getArgument(2);
        Set<String> ignored = new LinkedHashSet<>();
        if (options instanceof StringLiteralExpr literal) {
            ignored.add(literal.asString());
        } else if (options instanceof ArrayInitializerExpr array) {
            for (Expression value : array.getValues()) {
                if (value instanceof StringLiteralExpr literal) {
                    ignored.add(literal.asString());
                }
            }
        }
        return Set.copyOf(ignored);
    }

    // 作用：判断 Hutool 拷贝结果的精度；逻辑：无选项参数视为精确，选项类型无法确认时降为候选。
    private String hutoolCopyPrecision(MethodCallExpr call, JavaValueFlowContext context) {
        if (call.getArguments().size() < 3) {
            return "EXACT_STATIC";
        }
        Expression options = call.getArgument(2);
        String declared = context.declaredType(context.key(options));
        if (declared == null) {
            declared = context.declaredType(options.toString());
        }
        if (declared == null) {
            return "CANDIDATE";
        }
        String simple = declared;
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) {
            simple = simple.substring(dot + 1);
        }
        return "CopyOptions".equals(simple) ? "CANDIDATE" : "EXACT_STATIC";
    }

    // 作用：推导辅助方法的拷贝参数契约；逻辑：递归分析本地方法调用，把内部拷贝端点还原为外层参数下标。
    private Set<CopyContract> contracts(MethodDeclaration helper, Set<MethodDeclaration> visiting) {
        if (!visiting.add(helper)) {
            return Set.of();
        }
        JavaValueFlowContext helperContext = JavaValueFlowContext.from(helper);
        Set<CopyContract> contracts = new LinkedHashSet<>();
        for (MethodCallExpr call : helper.findAll(MethodCallExpr.class)) {
            if (!CallableOwnership.belongsToCallable(call, helper)) {
                continue;
            }
            CopyOperands direct = directCopyOperands(call, helperContext);
            if (direct != null) {
                addContract(contracts, helper, direct.source(), direct.target());
                continue;
            }
            MethodDeclaration nested = resolveLocalMethod(call, helper);
            if (nested == null) {
                continue;
            }
            for (CopyContract nestedContract : contracts(nested, visiting)) {
                if (nestedContract.sourceParameter() >= call.getArguments().size()
                        || nestedContract.targetParameter() >= call.getArguments().size()) {
                    continue;
                }
                addContract(contracts, helper,
                        call.getArgument(nestedContract.sourceParameter()),
                        call.getArgument(nestedContract.targetParameter()));
            }
        }
        visiting.remove(helper);
        return contracts;
    }

    // 作用：登记一条参数级拷贝契约；逻辑：仅当源、目标都能定位到不同参数下标时加入结果。
    private void addContract(
            Set<CopyContract> contracts,
            MethodDeclaration helper,
            Expression source,
            Expression target
    ) {
        int sourceIndex = parameterIndex(helper, source);
        int targetIndex = parameterIndex(helper, target);
        if (sourceIndex >= 0 && targetIndex >= 0 && sourceIndex != targetIndex) {
            contracts.add(new CopyContract(sourceIndex, targetIndex));
        }
    }

    // 作用：定位表达式对应的参数下标；逻辑：取根变量名并在方法参数列表中查找，未命中返回 -1。
    private int parameterIndex(MethodDeclaration method, Expression expression) {
        String name = CallableOwnership.rootName(expression);
        if (name == null) {
            return -1;
        }
        for (int index = 0; index < method.getParameters().size(); index++) {
            if (name.equals(method.getParameter(index).getNameAsString())) {
                return index;
            }
        }
        return -1;
    }

    // 作用：解析当前类内的本地方法；逻辑：按方法名与实参数量匹配唯一成员，非 this 调用或存在歧义时放弃。
    private MethodDeclaration resolveLocalMethod(MethodCallExpr call, com.github.javaparser.ast.body.CallableDeclaration<?> context) {
        if (call.getScope().filter(scope -> !scope.isThisExpr()).isPresent()) {
            return null;
        }
        TypeDeclaration<?> owner = context.findAncestor(TypeDeclaration.class).orElse(null);
        if (owner == null) {
            return null;
        }
        List<MethodDeclaration> matches = new ArrayList<>();
        for (BodyDeclaration<?> member : owner.getMembers()) {
            if (!member.isMethodDeclaration()) {
                continue;
            }
            MethodDeclaration method = member.asMethodDeclaration();
            if (method.getNameAsString().equals(call.getNameAsString())
                    && method.getParameters().size() == call.getArguments().size()) {
                matches.add(method);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    // 作用：判断编译单元是否导入指定类型；逻辑：遍历普通导入并做完整类名相等匹配。
    private boolean importsType(MethodCallExpr call, String qualifiedType) {
        return call.findCompilationUnit()
                .map(unit -> unit.getImports().stream()
                        .anyMatch(importDeclaration -> qualifiedType.equals(importDeclaration.getNameAsString())))
                .orElse(false);
    }

    // 作用：判断是否存在指定类型方法的静态导入；逻辑：遍历静态导入并匹配完整的“类型.方法名”。
    private boolean hasStaticImport(
            MethodCallExpr call,
            String qualifiedType,
            String methodName
    ) {
        return call.findCompilationUnit()
                .map(unit -> unit.getImports().stream()
                        .anyMatch(importDeclaration -> importDeclaration.isStatic()
                                && (qualifiedType + "." + methodName)
                                .equals(importDeclaration.getNameAsString())))
                .orElse(false);
    }

    private record CopyOperands(
            Expression source,
            Expression target,
            Set<String> ignoredFields,
            boolean hasIgnoreSpecification,
            String precision
    ) {
    }

    private record CopyContract(int sourceParameter, int targetParameter) {
    }

    private record SpringCopyArguments(
            Set<String> ignoredFields,
            boolean hasIgnoreSpecification,
            String precision
    ) {
    }
}
