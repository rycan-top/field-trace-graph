package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.Provenance;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.resolution.ExpressionTypeResolver;
import io.fieldtracegraph.javaanalysis.resolution.ExternalConstructorFieldCatalog;
import io.fieldtracegraph.javaanalysis.resolution.SourceTypeCatalog;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;

// 作用：提取构造器参数写入字段的值流。
// 使用场景：this.x = x 形式的对象初始化。

/**
 * Projects positional constructor arguments onto the fields assigned by the
 * source constructor. This closes the gap between an object-level
 * {@code new Type(arg)} edge and a concrete field such as
 * {@code param.pin}. Only source-proven assignments are emitted; Lombok or
 * external generated constructors remain outside this extractor.
 */
public final class ConstructorFieldFlowExtractor {
    private final ValueEdgeEmitter edgeEmitter = new ValueEdgeEmitter();
    private final ExpressionTypeResolver typeResolver;
    private final SourceTypeCatalog catalog;
    private final ExternalConstructorFieldCatalog externalCatalog;

    // 作用：构造完整提取器；逻辑：保存类型目录、表达式类型解析器与外部构造器字段目录。
    public ConstructorFieldFlowExtractor(
            SourceTypeCatalog catalog,
            ExternalConstructorFieldCatalog externalCatalog
    ) {
        this.typeResolver = new ExpressionTypeResolver(catalog);
        this.catalog = catalog;
        this.externalCatalog = externalCatalog;
    }

    // 作用：提取构造器字段流；逻辑：解析创建类型与参数绑定，把参数来源连到目标对象字段。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        int ordinal = 0;
        for (ObjectCreationExpr creation : scope.callable().findAll(ObjectCreationExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(creation, scope.callable())) {
                continue;
            }
            String typeQname = typeResolver.resolve(creation, scope, context);
            Map<Integer, String> bindings = typeQname == null
                    ? Map.of()
                    : catalog.constructorFieldBindings(typeQname, argumentTypes(creation, scope, context));
            Provenance provenance = Provenance.SOURCE;
            if (bindings.isEmpty() && externalCatalog != null) {
                bindings = externalCatalog.constructorFieldBindings(
                        creation,
                        argumentTypes(creation, scope, context)
                );
                provenance = Provenance.BYTECODE;
            }
            if (bindings.isEmpty()) {
                continue;
            }
            String sinkRoot = sinkRoot(creation, context);
            if (sinkRoot == null || sinkRoot.isBlank()) {
                continue;
            }
            for (Map.Entry<Integer, String> binding : bindings.entrySet()) {
                int argumentIndex = binding.getKey();
                if (argumentIndex < 0 || argumentIndex >= creation.getArguments().size()) {
                    continue;
                }
                String targetKey = sinkRoot + "." + binding.getValue();
                ordinal = edgeEmitter.emit(
                        source,
                        scope,
                        nodes,
                        accumulator,
                        context,
                        creation.getArgument(argumentIndex),
                        targetKey,
                        true,
                        creation,
                        ordinal,
                        "constructor parameter " + binding.getValue(),
                        provenance
                );
            }
        }
    }

    // 作用：推导参数类型列表；逻辑：优先用表达式类型解析，无法解析时按字面量类型兜底。
    private List<String> argumentTypes(
            ObjectCreationExpr creation,
            CallableScope scope,
            JavaValueFlowContext context
    ) {
        List<String> result = new ArrayList<>(creation.getArguments().size());
        for (Expression argument : creation.getArguments()) {
            String type = typeResolver.resolve(argument, scope, context);
            if (type == null) {
                type = literalType(argument);
            }
            result.add(type);
        }
        return java.util.Collections.unmodifiableList(result);
    }

    // 作用：判定字面量类型；逻辑：按字符串、整数、长整、浮点与布尔返回对应 Java 类型。
    private String literalType(Expression expression) {
        Expression value = unwrap(expression);
        if (value instanceof StringLiteralExpr) {
            return "java.lang.String";
        }
        if (value instanceof IntegerLiteralExpr) {
            return "int";
        }
        if (value instanceof LongLiteralExpr) {
            return "long";
        }
        if (value instanceof DoubleLiteralExpr) {
            return "double";
        }
        if (value instanceof BooleanLiteralExpr) {
            return "boolean";
        }
        return null;
    }

    // 作用：解析构造结果落点；逻辑：识别变量初始化、赋值、返回与调用参数，必要时用创建表达式标识。
    private String sinkRoot(ObjectCreationExpr creation, JavaValueFlowContext context) {
        Node current = creation;
        Node parent = current.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr || parent instanceof CastExpr) {
            current = parent;
            parent = current.getParentNode().orElse(null);
        }
        if (parent instanceof com.github.javaparser.ast.body.VariableDeclarator variable
                && variable.getInitializer().map(this::unwrap).filter(value -> value == creation).isPresent()) {
            return context.applyAlias(variable.getNameAsString());
        }
        if (parent instanceof AssignExpr assignment
                && unwrap(assignment.getValue()) == creation) {
            return context.key(assignment.getTarget());
        }
        if (parent instanceof ReturnStmt) {
            return "RETURN";
        }
        if (parent instanceof MethodCallExpr call
                && call.getArguments().stream().anyMatch(argument -> unwrap(argument) == creation)) {
            if (context.isSetter(call)) {
                return context.writeFieldPath(call);
            }
            // A constructor passed directly to a call has no local variable
            // name. Use its stable creation identity so field reads and call
            // argument binding can still address the same object.
            return context.key(creation);
        }
        return context.key(creation);
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
}
