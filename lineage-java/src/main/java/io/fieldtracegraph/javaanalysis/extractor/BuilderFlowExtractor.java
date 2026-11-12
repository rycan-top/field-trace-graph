package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.ReturnStmt;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.resolution.AccessorFieldIdentity;
import io.fieldtracegraph.javaanalysis.resolution.BuilderChainClassifier;

import java.util.List;
import java.util.Map;

// 作用：提取 Builder 链式赋值产生的值流。
// 使用场景：builder.x(v).build() 形式的对象构造。

/**
 * Projects fluent builder properties onto the object that receives the final
 * {@code build()} result. A builder mutator is a field contract even when the
 * generated builder class is outside the analysed source set.
 */
public final class BuilderFlowExtractor {
    private final ValueEdgeEmitter edgeEmitter = new ValueEdgeEmitter();

    // 作用：提取 Builder 流；逻辑：识别 build 调用的落点，把链上各属性赋值投影到目标对象字段。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        int ordinal = 0;
        for (MethodCallExpr build : scope.callable().findAll(MethodCallExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(build, scope.callable())
                    || !"build".equals(build.getNameAsString())
                    || !build.getArguments().isEmpty()) {
                continue;
            }
            FieldSink sink = fieldSink(build, context);
            List<MethodCallExpr> properties = BuilderChainClassifier.properties(build);
            if (sink == null || properties.isEmpty()) {
                continue;
            }
            for (MethodCallExpr property : properties) {
                ordinal = emitProperty(source, scope, nodes, accumulator, context,
                        sink, property, ordinal);
            }
        }
    }

    // 作用：发射单个属性边；逻辑：把属性参数来源连接到目标字段节点，并按落点选择边类型。
    private int emitProperty(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context,
            FieldSink sink,
            MethodCallExpr property,
            int ordinal
    ) {
        String propertyName = AccessorFieldIdentity.canonicalPropertyName(
                property.getNameAsString()
        );
        String targetKey = sink.root() + "." + propertyName;
        ValueNodeRecord target = edgeEmitter.ensure(
                source, scope, nodes, accumulator, targetKey, property
        );
        int result = ordinal;
        for (ExpressionFlowSources.Source input
                : ExpressionFlowSources.collect(property.getArgument(0), context)) {
            ValueNodeRecord origin = edgeEmitter.ensure(
                    source, scope, nodes, accumulator, input.key(), property.getArgument(0)
            );
            if (origin.valueId().equals(target.valueId())) {
                continue;
            }
            edgeEmitter.addEdge(
                    source,
                    scope,
                    accumulator,
                    origin,
                    target,
                    sink.returnSink() ? "ASSIGNED_FROM" : "FIELD_WRITE",
                    "builder property " + propertyName,
                    property,
                    result++
            );
        }
        return result;
    }

    // 作用：解析 build 结果落点；逻辑：识别变量初始化、赋值、返回与 setter 参数四种接收形态。
    private FieldSink fieldSink(MethodCallExpr build, JavaValueFlowContext context) {
        Node expression = build;
        Node parent = expression.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr || parent instanceof CastExpr) {
            expression = parent;
            parent = expression.getParentNode().orElse(null);
        }
        if (parent instanceof VariableDeclarator variable
                && variable.getInitializer().filter(value -> unwrap(value) == build).isPresent()) {
            return new FieldSink(variable.getNameAsString(), false);
        }
        if (parent instanceof AssignExpr assignment
                && unwrap(assignment.getValue()) == build) {
            return new FieldSink(context.key(assignment.getTarget()), false);
        }
        if (parent instanceof ReturnStmt) {
            return new FieldSink("RETURN", true);
        }
        if (parent instanceof MethodCallExpr setter
                && context.isSetter(setter)
                && unwrap(setter.getArgument(0)) == build) {
            String path = context.writeFieldPath(setter);
            return new FieldSink(path, false);
        }
        return null;
    }

    // 作用：去除表达式包装；逻辑：循环剥离括号与强制类型转换。
    private Expression unwrap(Expression expression) {
        Expression value = expression;
        while (true) {
            if (value instanceof EnclosedExpr enclosed) {
                value = enclosed.getInner();
            } else if (value instanceof CastExpr cast) {
                value = cast.getExpression();
            } else {
                return value;
            }
        }
    }

    private record FieldSink(String root, boolean returnSink) {
    }
}
