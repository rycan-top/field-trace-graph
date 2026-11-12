package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import io.fieldtracegraph.core.model.CallEdgeRecord;
import io.fieldtracegraph.core.model.PendingRefRecord;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.resolution.CallResolution;
import io.fieldtracegraph.javaanalysis.resolution.MethodCallResolver;
import io.fieldtracegraph.javaanalysis.resolution.BuilderChainClassifier;
import io.fieldtracegraph.javaanalysis.resolution.NonLineageCallFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

// 作用：提取源码中的调用点。
// 使用场景：生成调用边，支撑跨方法追踪。

public final class CallSiteExtractor {
    private final NonLineageCallFilter nonLineageCallFilter = new NonLineageCallFilter();

    // 作用：提取调用点；逻辑：过滤非血缘调用后解析普通调用、对象创建与显式构造器调用并写边。
    public void extract(
            SourceFileRecord source,
            CompilationUnit unit,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            MethodCallResolver resolver,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        Map<String, String> localTypes = context.variableTypes();
        int sequence = 0;
        for (MethodCallExpr call : scope.callable().findAll(MethodCallExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())) {
                continue;
            }
            boolean ignored = nonLineageCallFilter.shouldIgnore(call, context);
            if (ignored && nonLineageCallFilter.isRetainedControlCall(call, scope.callable())) {
                ignored = false;
            }
            if (ignored
                    || context.isGetter(call)
                    || context.isSetter(call)
                    || BuilderChainClassifier.isBuilderCall(call)
                    || context.isIntermediateStreamCall(call)) {
                continue;
            }
            sequence++;
            CallResolution resolution = resolver.resolveMethodCall(source, unit, scope, localTypes, call);
            String dispatchKind = resolver.dispatchKind(unit, scope, localTypes, call);
            addCall(source, scope, nodes, accumulator, context, resolution, dispatchKind, call,
                    receiverValueId(call, nodes, context), sequence);
        }
        for (ObjectCreationExpr creation : scope.callable().findAll(ObjectCreationExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(creation, scope.callable())) {
                continue;
            }
            sequence++;
            CallResolution resolution = resolver.resolveConstructor(source, unit, creation);
            addCall(source, scope, nodes, accumulator, context, resolution, "CONSTRUCTOR", creation,
                    resultValueId(creation, nodes, context), sequence);
        }
        for (ExplicitConstructorInvocationStmt invocation : scope.callable().findAll(ExplicitConstructorInvocationStmt.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(invocation, scope.callable())) {
                continue;
            }
            sequence++;
            CallResolution resolution = resolver.resolveExplicitConstructorInvocation(source, unit, scope, invocation);
            addConstructorInvocation(source, scope, nodes, accumulator, context, resolution, invocation, sequence);
        }
    }

    // 作用：记录显式构造器调用；逻辑：生成调用边，未解析或候选时同时写入待解析引用。
    private void addConstructorInvocation(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            CallResolution resolution,
            ExplicitConstructorInvocationStmt invocation,
            int sequence
    ) {
        CallResolution effective = exactStaticResolution(resolution, "CONSTRUCTOR");
        int line = invocation.getBegin().map(position -> position.line).orElse(1);
        int column = invocation.getBegin().map(position -> position.column).orElse(1);
        String callSiteId = Ids.sha256(scope.symbolId(), effective.targetQname(), String.valueOf(line));
        String edgeId = Ids.sha256(scope.symbolId(), effective.targetQname(), invocation.toString(), String.valueOf(sequence));
        List<String> argumentValueIds = invocation.getArguments().stream()
                .map(argument -> {
                    ValueNodeRecord node = nodes.get(context.key(argument));
                    return node == null ? null : node.valueId();
                })
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        accumulator.addCallEdge(new CallEdgeRecord(
                edgeId,
                callSiteId,
                scope.symbolId(),
                effective.calleeSymbolId(),
                effective.targetQname(),
                "CONSTRUCTOR",
                effective.resolution(),
                null,
                argumentValueIds,
                null,
                source.fileId(),
                line,
                column,
                invocation.getEnd().map(position -> position.line).orElse(1),
                invocation.getEnd().map(position -> position.column).orElse(1)
        ));
        if ("CANDIDATE".equals(effective.resolution()) || "UNRESOLVED".equals(effective.resolution())) {
            accumulator.addPendingRef(new PendingRefRecord(
                    Ids.sha256(scope.symbolId(), "pending", effective.targetQname(), invocation.toString(), String.valueOf(sequence)),
                    callSiteId,
                    scope.symbolId(),
                    "CONSTRUCTOR",
                    effective.targetQname(),
                    effective.candidateQnames(),
                    effective.reason() == null ? "EXTERNAL" : effective.reason(),
                    source.fileId(),
                    line,
                    column,
                    invocation.getEnd().map(position -> position.line).orElse(1),
                    invocation.getEnd().map(position -> position.column).orElse(1),
                    "CONSTRUCTOR",
                    null,
                    argumentValueIds,
                    null
            ));
        }
    }

    // 作用：记录普通调用；逻辑：生成调用边并保留接收者、参数与结果值，候选或未解析时写待解析引用。
    private void addCall(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            CallResolution resolution,
            String dispatchKind,
            Expression call,
            String receiverValueId,
            int sequence
    ) {
        CallResolution effective = exactStaticResolution(resolution, dispatchKind);
        int line = call.getBegin().map(position -> position.line).orElse(1);
        int column = call.getBegin().map(position -> position.column).orElse(1);
        String callSiteId = Ids.sha256(scope.symbolId(), effective.targetQname(), String.valueOf(line));
        String edgeId = Ids.sha256(scope.symbolId(), effective.targetQname(), call.toString(), String.valueOf(sequence));
        List<String> argumentValueIds = argumentValueIds(call, nodes, context);
        String resultValueId = resultValueId(call, nodes, context);
        accumulator.addCallEdge(new CallEdgeRecord(
                edgeId,
                callSiteId,
                scope.symbolId(),
                effective.calleeSymbolId(),
                effective.targetQname(),
                dispatchKind,
                effective.resolution(),
                receiverValueId,
                argumentValueIds,
                resultValueId,
                source.fileId(),
                line,
                column,
                call.getEnd().map(position -> position.line).orElse(1),
                call.getEnd().map(position -> position.column).orElse(1)
        ));
        if ("CANDIDATE".equals(effective.resolution()) || "UNRESOLVED".equals(effective.resolution())) {
            accumulator.addPendingRef(new PendingRefRecord(
                    Ids.sha256(scope.symbolId(), "pending", effective.targetQname(), call.toString(), String.valueOf(sequence)),
                    callSiteId,
                    scope.symbolId(),
                    "CONSTRUCTOR".equals(dispatchKind) ? "CONSTRUCTOR" : "METHOD",
                    effective.targetQname(),
                    effective.candidateQnames(),
                    effective.reason() == null ? "EXTERNAL" : effective.reason(),
                    source.fileId(),
                    line,
                    column,
                    call.getEnd().map(position -> position.line).orElse(1),
                    call.getEnd().map(position -> position.column).orElse(1),
                    dispatchKind,
                    receiverValueId,
                    argumentValueIds,
                    resultValueId
            ));
        }
    }

    // 作用：提升编译期确定调用；逻辑：静态分派或构造器的编译期目标升级为精确静态，其余保持原级别。
    private CallResolution exactStaticResolution(CallResolution resolution, String dispatchKind) {
        if ("COMPILE_TIME_TARGET".equals(resolution.resolution())
                && ("STATIC".equals(dispatchKind) || "CONSTRUCTOR".equals(dispatchKind))) {
            return resolution.asExactStatic();
        }
        return resolution;
    }

    // 作用：取接收者值 ID；逻辑：解析调用作用域对应节点，缺失时返回 null。
    private String receiverValueId(Expression call, Map<String, ValueNodeRecord> nodes, JavaValueFlowContext context) {
        if (!(call instanceof MethodCallExpr methodCall)) {
            return null;
        }
        Expression receiver = methodCall.getScope().orElse(null);
        if (receiver == null) {
            return null;
        }
        ValueNodeRecord node = nodes.get(context.key(receiver));
        return node == null ? null : node.valueId();
    }

    // 作用：取参数值 ID 列表；逻辑：按调用或创建的参数顺序映射节点 ID，缺失位保留 null。
    private List<String> argumentValueIds(Expression call, Map<String, ValueNodeRecord> nodes, JavaValueFlowContext context) {
        List<String> result = new ArrayList<>();
        if (call instanceof MethodCallExpr methodCall) {
            for (Expression argument : methodCall.getArguments()) {
                ValueNodeRecord node = nodes.get(context.key(argument));
                result.add(node == null ? null : node.valueId());
            }
        } else if (call instanceof ObjectCreationExpr creation) {
            for (Expression argument : creation.getArguments()) {
                ValueNodeRecord node = nodes.get(context.key(argument));
                result.add(node == null ? null : node.valueId());
            }
        }
        return Collections.unmodifiableList(result);
    }

    // 作用：取调用结果值 ID；逻辑：按表达式键查找结果节点，缺失时返回 null。
    private String resultValueId(Expression call, Map<String, ValueNodeRecord> nodes, JavaValueFlowContext context) {
        String key = context.key(call);
        ValueNodeRecord node = nodes.get(key);
        return node == null ? null : node.valueId();
    }
}
