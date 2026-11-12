package io.fieldtracegraph.javaanalysis.resolution;

import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;

// 作用：解析表达式的静态类型。
// 使用场景：泛型、数组、装箱与链式调用中的类型判断。

/**
 * Resolves the catalog type of a value expression. This is intentionally
 * separate from call dispatch: callers use the declared value type for
 * property projection, bean-copy expansion and other data-shape decisions.
 */
public final class ExpressionTypeResolver {
    private final SourceTypeCatalog catalog;
    private final JavaTypeNameResolver typeNameResolver;
    private final TypeArgumentResolver typeArguments;

    // 作用：创建表达式类型解析器；逻辑：保存类型目录并初始化类型名与泛型参数解析器。
    public ExpressionTypeResolver(SourceTypeCatalog catalog) {
        this.catalog = catalog;
        this.typeNameResolver = new JavaTypeNameResolver(catalog);
        this.typeArguments = new TypeArgumentResolver(catalog);
    }

    // 作用：解析表达式的静态类型全名；逻辑：优先使用上下文声明类型，再按构造、强转、变量、字段访问和方法返回类型推断。
    public String resolve(
            Expression expression,
            CallableScope scope,
            JavaValueFlowContext context
    ) {
        if (expression == null) {
            return null;
        }
        Expression value = unwrap(expression);
        String declared = context.declaredType(context.key(value));
        if (declared != null && !declared.isBlank()) {
            String resolved = resolveType(value, declared);
            if (resolved != null) {
                return resolved;
            }
        }
        if (value instanceof ObjectCreationExpr creation) {
            return resolveType(value, creation.getType().asString());
        }
        if (value instanceof CastExpr cast) {
            return resolveType(value, cast.getType().asString());
        }
        if (value instanceof NameExpr name) {
            return resolveType(value, name.getNameAsString());
        }
        if (value instanceof FieldAccessExpr field) {
            String ownerExpression = resolveTypeExpression(field.getScope(), scope, context);
            if (ownerExpression != null) {
                String genericType = typeArguments.fieldType(
                        ownerExpression,
                        field.getNameAsString()
                );
                if (genericType != null) {
                    return resolveType(value, genericType);
                }
            }
            String owner = resolve(field.getScope(), scope, context);
            return owner == null
                    ? null
                    : resolveType(value, catalog.fieldTypeInHierarchy(owner, field.getNameAsString()));
        }
        if (value instanceof MethodCallExpr call) {
            String ownerExpression = call.getScope()
                    .map(receiver -> resolveTypeExpression(receiver, scope, context))
                    .orElse(scope.typeQname());
            if (ownerExpression != null) {
                java.util.List<String> argumentTypes = call.getArguments().stream()
                        .map(argument -> resolveTypeExpression(argument, scope, context))
                        .toList();
                String genericType = typeArguments.methodReturnType(
                        ownerExpression,
                        call.getNameAsString(),
                        call.getArguments().size(),
                        argumentTypes
                );
                if (genericType != null) {
                    String resolved = resolveType(value, genericType);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            }
            String owner = call.getScope()
                    .map(receiver -> resolve(receiver, scope, context))
                    .orElse(scope.typeQname());
            if (owner == null) {
                return null;
            }
            String returnType = catalog.methodReturnType(
                    owner, call.getNameAsString(), call.getArguments().size()
            );
            if (returnType != null) {
                return resolveType(value, returnType);
            }
            String property = AccessorFieldIdentity.propertyFromAccessor(
                    call.getNameAsString(), true
            );
            return property == null
                    ? null
                    : resolveType(value, catalog.fieldTypeInHierarchy(owner, property));
        }
        return null;
    }

    /**
     * Returns the declared type expression without erasing generic arguments.
     * Callers that only need the catalog qname should continue to use
     * {@link #resolve(Expression, CallableScope, JavaValueFlowContext)}.
     */
    // 作用：解析不擦除泛型的类型表达式；逻辑：按声明类型、构造、强转、变量、字段与链式方法返回类型逐项推导。
    public String resolveTypeExpression(
            Expression expression,
            CallableScope scope,
            JavaValueFlowContext context
    ) {
        if (expression == null) {
            return null;
        }
        Expression value = unwrap(expression);
        String declared = context.declaredType(context.key(value));
        if (declared != null && !declared.isBlank()) {
            return declared;
        }
        if (value instanceof ObjectCreationExpr creation) {
            return creation.getType().asString();
        }
        if (value instanceof CastExpr cast) {
            return cast.getType().asString();
        }
        if (value instanceof NameExpr name) {
            return context.declaredType(name.getNameAsString());
        }
        if (value instanceof FieldAccessExpr field) {
            String owner = resolveTypeExpression(field.getScope(), scope, context);
            return owner == null
                    ? null
                    : typeArguments.fieldType(owner, field.getNameAsString());
        }
        if (value instanceof MethodCallExpr call) {
            String owner = call.getScope()
                    .map(receiver -> resolveTypeExpression(receiver, scope, context))
                    .orElse(scope.typeQname());
            if (owner == null) {
                return null;
            }
            String returnType = typeArguments.methodReturnType(
                    owner,
                    call.getNameAsString(),
                    call.getArguments().size(),
                    call.getArguments().stream()
                            .map(argument -> resolveTypeExpression(argument, scope, context))
                            .toList()
            );
            if (returnType != null) {
                return returnType;
            }
            String property = AccessorFieldIdentity.propertyFromAccessor(
                    call.getNameAsString(),
                    true
            );
            return property == null ? null : typeArguments.fieldType(owner, property);
        }
        return null;
    }

    // 作用：把声明类型文本规范化到类型目录；逻辑：优先使用编译单元的导入上下文，无法定位时回退到全局类型名。
    private String resolveType(Expression expression, String declaredType) {
        if (declaredType == null || declaredType.isBlank()) {
            return null;
        }
        return expression.findCompilationUnit()
                .map(unit -> typeNameResolver.resolve(unit, declaredType))
                .orElseGet(() -> {
                    String normalized = JavaTypeNameResolver.normalize(declaredType);
                    return normalized != null && normalized.contains(".")
                            ? normalized
                            : catalog.resolveTypeName(normalized);
                });
    }

    // 作用：解包表达式外层结构；逻辑：循环去掉括号与强转，返回真实值表达式。
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
