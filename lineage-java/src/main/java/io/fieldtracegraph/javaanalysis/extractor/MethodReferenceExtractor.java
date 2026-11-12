package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import io.fieldtracegraph.core.model.CallEdgeRecord;
import io.fieldtracegraph.core.model.PendingRefRecord;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.resolution.SourceTypeCatalog;

import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：提取方法引用。
// 使用场景：User::getName 形式在函数式调用中的值流。

/**
 * Persists method-reference call sites as graph edges. This is the functional
 * interface counterpart of a normal method call and prevents {@code A::m}
 * from becoming an unexplained boundary.
 */
public final class MethodReferenceExtractor {
    private final SourceTypeCatalog catalog;
    private final ValueNodeCatalog nodeCatalog = new ValueNodeCatalog();
    private final ValueEdgeEmitter edgeEmitter = new ValueEdgeEmitter();

    // 作用：注入类型目录；逻辑：保存用于解析方法引用目标的源码类型目录。
    public MethodReferenceExtractor(SourceTypeCatalog catalog) {
        this.catalog = catalog;
    }

    // 作用：提取方法引用；逻辑：遍历作用域内引用，逐个解析目标并写入调用边或待解析引用。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        int sequence = 0;
        for (MethodReferenceExpr reference : scope.callable().findAll(MethodReferenceExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(reference, scope.callable())) {
                continue;
            }
            sequence++;
            emit(source, scope, nodes, accumulator, context, reference, sequence);
        }
    }

    // 作用：处理方法引用；逻辑：解析所有者类型与候选方法，建立引用节点、调用边及歧义待解析记录。
    private void emit(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            MethodReferenceExpr reference,
            int sequence
    ) {
        String ownerType = ownerType(reference.getScope(), scope, context);
        String referenceKey = context.key(reference);
        ValueNodeRecord referenceNode = nodeCatalog.ensure(
                source, scope, nodes, accumulator, referenceKey, "EXPRESSION", reference
        );
        bindReceiver(source, scope, nodes, accumulator, context, reference, referenceNode);
        if (ownerType == null) {
            addPending(source, scope, accumulator, reference, sequence, List.of(), "UNRESOLVED");
            return;
        }
        List<SourceTypeCatalog.MethodInfo> candidates = "new".equals(reference.getIdentifier())
                ? catalog.findConstructors(ownerType)
                : catalog.findMethods(ownerType, reference.getIdentifier());
        if (candidates.isEmpty()) {
            addPending(
                    source,
                    scope,
                    accumulator,
                    reference,
                    sequence,
                    List.of(),
                    catalog.hasGeneratedMembers(ownerType)
                            ? "GENERATED_CODE_MISSING"
                            : "CALLEE_SYMBOL_MISSING"
            );
            return;
        }
        SourceTypeCatalog.MethodInfo target = candidates.get(0);
        String resolution = candidates.size() == 1 ? "EXACT_STATIC" : "CANDIDATE";
        String receiverValueId = receiverValueId(reference.getScope(), nodes, context);
        StreamApplication application = streamApplication(reference, source, scope, nodes, accumulator, context);
        int line = reference.getBegin().map(position -> position.line).orElse(1);
        int column = reference.getBegin().map(position -> position.column).orElse(1);
        String callSiteId = Ids.sha256(scope.symbolId(), target.qname(), String.valueOf(line));
        accumulator.addCallEdge(new CallEdgeRecord(
                Ids.sha256(scope.symbolId(), target.qname(), reference.toString(), String.valueOf(sequence)),
                callSiteId,
                scope.symbolId(),
                "CANDIDATE".equals(resolution) ? null : target.symbolId(),
                target.qname(),
                "METHOD_REFERENCE",
                resolution,
                receiverValueId,
                application.argumentValueIds(),
                application.resultValueId(),
                source.fileId(),
                line,
                column,
                reference.getEnd().map(position -> position.line).orElse(line),
                reference.getEnd().map(position -> position.column).orElse(column)
        ));
        if ("CANDIDATE".equals(resolution)) {
            addPending(source, scope, accumulator, reference, sequence,
                    candidates.stream().map(SourceTypeCatalog.MethodInfo::qname).toList(),
                    "OVERLOAD_AMBIGUOUS");
        }
    }

    /**
     * A method reference used by {@code map}/{@code flatMap} consumes one
     * stream element and produces the element of the mapped stream. Recording
     * that application as a call edge lets the query layer use the same
     * parameter and RETURN bridges as an explicit lambda call.
     */
    // 作用：构建流映射应用；逻辑：把 map 或 flatMap 的输入元素与输出元素作为调用实参与结果。
    private StreamApplication streamApplication(
            MethodReferenceExpr reference,
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        MethodCallExpr mappingCall = reference.findAncestor(MethodCallExpr.class)
                .filter(call -> Set.of("map", "flatMap").contains(call.getNameAsString()))
                .filter(call -> call.getArguments().stream().anyMatch(argument -> argument == reference))
                .orElse(null);
        if (mappingCall == null) {
            return StreamApplication.EMPTY;
        }
        MethodCallExpr chainCall = outermostStreamCall(mappingCall, context);
        String sourceElement = context.streamElementKey(chainCall);
        String resultElement = context.streamResultElementKey(chainCall);
        if (sourceElement == null || resultElement == null) {
            return StreamApplication.EMPTY;
        }
        ValueNodeRecord sourceNode = nodeCatalog.ensure(
                source, scope, nodes, accumulator, sourceElement, "CONTAINER_ELEMENT", reference
        );
        ValueNodeRecord resultNode = nodeCatalog.ensure(
                source, scope, nodes, accumulator, resultElement, "CONTAINER_ELEMENT", reference
        );
        return new StreamApplication(List.of(sourceNode.valueId()), resultNode.valueId());
    }

    // 作用：定位最外层流调用；逻辑：沿父调用向上，直到不再属于同一流链。
    private MethodCallExpr outermostStreamCall(
            MethodCallExpr mappingCall,
            JavaValueFlowContext context
    ) {
        MethodCallExpr current = mappingCall;
        while (true) {
            com.github.javaparser.ast.Node parent = current.getParentNode().orElse(null);
            if (!(parent instanceof MethodCallExpr outer)
                    || outer.getScope().map(this::unwrap).orElse(null) != current
                    || context.streamChain(outer).isEmpty()) {
                return current;
            }
            current = outer;
        }
    }

    // 作用：去除表达式包装；逻辑：循环剥离括号与强制类型转换。
    private Expression unwrap(Expression expression) {
        Expression value = expression;
        while (value instanceof com.github.javaparser.ast.expr.EnclosedExpr enclosed
                || value instanceof com.github.javaparser.ast.expr.CastExpr) {
            value = value instanceof com.github.javaparser.ast.expr.EnclosedExpr enclosed
                    ? enclosed.getInner()
                    : ((com.github.javaparser.ast.expr.CastExpr) value).getExpression();
        }
        return value;
    }

    // 作用：登记待解析引用；逻辑：生成稳定 ID，记录方法引用目标、候选与失败原因。
    private void addPending(
            SourceFileRecord source,
            CallableScope scope,
            JavaAnalysisAccumulator accumulator,
            MethodReferenceExpr reference,
            int sequence,
            List<String> candidates,
            String reason
    ) {
        int line = reference.getBegin().map(position -> position.line).orElse(1);
        int column = reference.getBegin().map(position -> position.column).orElse(1);
        String target = reference.getScope() + "::" + reference.getIdentifier();
        accumulator.addPendingRef(new PendingRefRecord(
                Ids.sha256(scope.symbolId(), "pending-ref", target, String.valueOf(sequence)),
                Ids.sha256(scope.symbolId(), target, String.valueOf(line)),
                scope.symbolId(),
                "METHOD_REFERENCE",
                target,
                candidates,
                reason,
                source.fileId(),
                line,
                column,
                reference.getEnd().map(position -> position.line).orElse(line),
                reference.getEnd().map(position -> position.column).orElse(column),
                "METHOD_REFERENCE",
                null,
                List.of(),
                null
        ));
    }

    // 作用：解析引用所有者类型；逻辑：按类型表达式、this、变量与字段访问分别取声明类型并解析全名。
    private String ownerType(Expression receiver, CallableScope scope, JavaValueFlowContext context) {
        if (receiver instanceof TypeExpr type) {
            String typeText = type.getType().asString();
            String declaredType = context.declaredType(typeText);
            return resolveType(declaredType == null ? typeText : declaredType);
        }
        if (receiver instanceof com.github.javaparser.ast.expr.ThisExpr) {
            return scope.typeQname();
        }
        if (receiver instanceof NameExpr name) {
            String declared = context.declaredType(name.getNameAsString());
            if (declared == null) {
                declared = name.getNameAsString();
            }
            return resolveType(declared);
        }
        if (receiver instanceof FieldAccessExpr field) {
            String declared = context.declaredType(field.toString());
            return declared == null ? null : resolveType(declared);
        }
        return null;
    }

    // 作用：解析类型名；逻辑：去掉泛型后查目录，失败时在目录已有该类型的前提下返回原名。
    private String resolveType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }
        String simple = typeName;
        int generic = simple.indexOf('<');
        if (generic >= 0) {
            simple = simple.substring(0, generic);
        }
        String resolved = catalog.resolveTypeName(simple);
        return resolved == null ? (catalog.type(simple) == null ? null : simple) : resolved;
    }

    // 作用：取引用接收者值 ID；逻辑：静态类型限定无实际值时返回 null，其余按表达式键取节点。
    private String receiverValueId(
            Expression receiver,
            Map<String, ValueNodeRecord> nodes,
            JavaValueFlowContext context
    ) {
        if (receiver instanceof TypeExpr) {
            return context.declaredType(receiver.toString()) == null
                    ? null
                    : nodeId(receiver, nodes, context);
        }
        return nodeId(receiver, nodes, context);
    }

    // 作用：按表达式取节点 ID；逻辑：查表后返回节点 ID，缺失时返回 null。
    private String nodeId(
            Expression receiver,
            Map<String, ValueNodeRecord> nodes,
            JavaValueFlowContext context
    ) {
        ValueNodeRecord node = nodes.get(context.key(receiver));
        return node == null ? null : node.valueId();
    }

    // 作用：判断是否为静态类型接收者；逻辑：类型表达式且未声明为变量时视为静态限定。
    private boolean isStaticTypeReceiver(Expression receiver, JavaValueFlowContext context) {
        return receiver instanceof TypeExpr
                && context.declaredType(receiver.toString()) == null;
    }

    // 作用：绑定引用接收者；逻辑：跳过静态类型，其余把接收者来源连到方法引用节点。
    private void bindReceiver(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            MethodReferenceExpr reference,
            ValueNodeRecord referenceNode
    ) {
        Expression receiver = reference.getScope();
        if (isStaticTypeReceiver(receiver, context)) {
            return;
        }
        for (ExpressionFlowSources.Source input : ExpressionFlowSources.collect(receiver, context)) {
            ValueNodeRecord origin = nodeCatalog.ensure(
                    source, scope, nodes, accumulator,
                    input.key(), "EXPRESSION", receiver
            );
            if (!origin.valueId().equals(referenceNode.valueId())) {
                edgeEmitter.addEdge(
                        source, scope, accumulator, origin, referenceNode,
                        "DERIVED_FROM", "method reference receiver", reference, 0
                );
            }
        }
    }

    private record StreamApplication(List<String> argumentValueIds, String resultValueId) {
        private static final StreamApplication EMPTY = new StreamApplication(List.of(), null);
    }
}
