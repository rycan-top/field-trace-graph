package io.fieldtracegraph.javaanalysis.resolution;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import io.fieldtracegraph.javaanalysis.CallableScope;

import java.util.Map;

// 作用：解析方法调用接收者的类型。
// 使用场景：a.b().c() 等链式调用中确定每一步的接收者。

/**
 * Resolves the declared type of a method-call receiver such as
 * {@code obtainBondAbility}, {@code this.service} or {@code factory.create()}.
 *
 * <p>The lookup order mirrors Java scoping rules: method parameters and local
 * variables first, then fields of the declaring type (including inherited
 * fields), and only then a type name used for a static call. Getting this order
 * right is what separates an injected instance field from a static utility
 * class, which in turn decides {@code VIRTUAL} versus {@code STATIC} dispatch.</p>
 */
public final class ReceiverTypeResolver {
    private final SourceTypeCatalog catalog;
    private final JavaTypeNameResolver typeNameResolver;

    // 作用：创建接收者类型解析器；逻辑：保存类型目录并初始化类型名解析器。
    public ReceiverTypeResolver(SourceTypeCatalog catalog) {
        this.catalog = catalog;
        this.typeNameResolver = new JavaTypeNameResolver(catalog);
    }

    /**
     * @return the catalog qname of the receiver type, or {@code null} when the
     * type is outside the analysed source set.
     */
    // 作用：解析接收者表达式的声明类型；逻辑：按 this/super、局部变量、字段、构造、强转与链式调用依次判定。
    public String resolve(
            CompilationUnit unit,
            CallableScope scope,
            Map<String, String> localTypes,
            Expression receiver
    ) {
        if (receiver == null || receiver instanceof ThisExpr) {
            return scope.typeQname();
        }
        if (receiver instanceof SuperExpr) {
            return firstSuperType(scope.typeQname());
        }
        if (receiver instanceof NameExpr name) {
            String local = localTypes.get(name.getNameAsString());
            if (local != null) {
                return typeNameResolver.resolve(unit, local);
            }
            String fieldType = fieldTypeInHierarchy(scope.typeQname(), name.getNameAsString());
            if (fieldType != null) {
                return typeNameResolver.resolve(unit, fieldType);
            }
            return typeNameResolver.resolve(unit, name.getNameAsString());
        }
        if (receiver instanceof FieldAccessExpr fieldAccess) {
            String ownerType = resolve(unit, scope, localTypes, fieldAccess.getScope());
            if (ownerType == null) {
                return null;
            }
            String fieldType = catalog.fieldTypeInHierarchy(ownerType, fieldAccess.getNameAsString());
            return fieldType == null ? null : typeNameResolver.resolve(unit, fieldType);
        }
        if (receiver instanceof ObjectCreationExpr creation) {
            return typeNameResolver.resolve(unit, creation.getType().asString());
        }
        if (receiver instanceof CastExpr cast) {
            return typeNameResolver.resolve(unit, cast.getType().asString());
        }
        if (receiver instanceof EnclosedExpr enclosed) {
            return resolve(unit, scope, localTypes, enclosed.getInner());
        }
        if (receiver instanceof MethodCallExpr chained) {
            return chainedReceiverType(unit, scope, localTypes, chained);
        }
        return null;
    }

    /**
     * A receiver is an instance when it is a value expression. A bare name that
     * cannot be found as a local, parameter or field is treated as a type name
     * and therefore as a static call.
     */
    // 作用：判断接收者是否为实例值；逻辑：局部变量与字段视为实例，无法解析的裸名称视为静态类型调用。
    public boolean isInstanceReceiver(
            CompilationUnit unit,
            CallableScope scope,
            Map<String, String> localTypes,
            Expression receiver
    ) {
        if (receiver == null || receiver instanceof ThisExpr || receiver instanceof SuperExpr) {
            return true;
        }
        if (receiver instanceof NameExpr name) {
            if (localTypes.containsKey(name.getNameAsString())) {
                return true;
            }
            return fieldTypeInHierarchy(scope.typeQname(), name.getNameAsString()) != null;
        }
        return true;
    }

    // 作用：在类型继承链中查字段类型；逻辑：直接委托类型目录完成层级查找。
    public String fieldTypeInHierarchy(String typeQname, String fieldName) {
        return catalog.fieldTypeInHierarchy(typeQname, fieldName);
    }

    // 作用：解析链式调用结果的类型；逻辑：先求接收者类型，再按方法名与实参数量查返回类型并规范化。
    private String chainedReceiverType(
            CompilationUnit unit,
            CallableScope scope,
            Map<String, String> localTypes,
            MethodCallExpr call
    ) {
        String ownerType;
        if (call.getScope().isEmpty()) {
            ownerType = scope.typeQname();
        } else {
            ownerType = resolve(unit, scope, localTypes, call.getScope().orElseThrow());
        }
        if (ownerType == null) {
            return null;
        }
        String returnType = catalog.methodReturnType(ownerType, call.getNameAsString(), call.getArguments().size());
        return returnType == null ? null : typeNameResolver.resolve(unit, returnType);
    }

    // 作用：取类型的直接父类型；逻辑：无显式父类时返回 Object，否则优先目录全名并回退到源码父类型表达式。
    private String firstSuperType(String typeQname) {
        SourceTypeCatalog.TypeInfo type = catalog.type(typeQname);
        if (type == null) {
            return null;
        }
        if (type.superTypes().isEmpty()) {
            return "java.lang.Object";
        }
        String qname = type.superTypes().get(0);
        if (catalog.type(qname) != null) {
            return qname;
        }
        if (type.superTypeExpressions().isEmpty()) {
            return qname;
        }
        String expression = type.superTypeExpressions().get(0);
        return expression == null || expression.isBlank() ? qname : expression;
    }
}
