package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.SymbolRecord;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.JavaSymbolIdentity;
import io.fieldtracegraph.javaanalysis.JavaTypeKind;

import java.util.ArrayList;
import java.util.List;

// 作用：构建方法、构造器与 Lambda 的作用域树。
// 使用场景：嵌套类与 Lambda 中确定语句归属。

public final class CallableScopeExtractor {
    // 作用：提取作用域与符号；逻辑：遍历类型成员登记类型与方法，并补齐记录构造器、隐式构造器与初始化块。
    public List<CallableScope> extract(
            SourceFileRecord source,
            CompilationUnit unit,
            JavaAnalysisAccumulator accumulator
    ) {
        String packageName = unit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString())
                .orElse("");
        List<CallableScope> scopes = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            String typeQname = type.getFullyQualifiedName().orElse(type.getNameAsString());
            String classSymbolId = JavaSymbolIdentity.classSymbolId(source.moduleId(), typeQname);
            accumulator.addSymbol(classSymbol(source, typeQname, packageName, classSymbolId, type));
            boolean hasConstructor = false;
            boolean hasCanonicalRecordConstructor = false;
            for (BodyDeclaration<?> member : type.getMembers()) {
                if (member instanceof MethodDeclaration method) {
                    scopes.add(extractCallable(source, classSymbolId, typeQname, method, accumulator));
                } else if (member instanceof ConstructorDeclaration constructor) {
                    hasConstructor = true;
                    if (JavaSymbolIdentity.isCompactRecordConstructor(type, constructor)) {
                        RecordDeclaration record = (RecordDeclaration) type;
                        scopes.add(extractCallable(
                                source,
                                classSymbolId,
                                typeQname,
                                recordCanonicalConstructor(record, constructor),
                                accumulator
                        ));
                        hasCanonicalRecordConstructor = true;
                    } else {
                        if (JavaSymbolIdentity.isCanonicalRecordConstructor(type, constructor)) {
                            hasCanonicalRecordConstructor = true;
                        }
                        scopes.add(extractCallable(
                                source, classSymbolId, typeQname, constructor, accumulator
                        ));
                    }
                }
            }
            if (type instanceof RecordDeclaration record && !hasCanonicalRecordConstructor) {
                scopes.add(extractCallable(
                        source,
                        classSymbolId,
                        typeQname,
                        recordCanonicalConstructor(record, null),
                        accumulator
                ));
            } else if (!hasConstructor && JavaSymbolIdentity.needsImplicitConstructor(type)) {
                scopes.add(extractCallable(source, classSymbolId, typeQname, implicitConstructor(type), accumulator));
            }
            addInitializerScopes(source, classSymbolId, typeQname, type, scopes, accumulator);
        }
        return scopes;
    }

    // 作用：创建隐式无参构造器；逻辑：生成与类型同名、空方法体的构造器声明。
    private ConstructorDeclaration implicitConstructor(TypeDeclaration<?> type) {
        return new ConstructorDeclaration()
                .setName(type.getNameAsString())
                .setBody(new BlockStmt());
    }

    /**
     * Records always expose a canonical constructor even when the source does
     * not declare one. A compact constructor is represented by a synthetic
     * canonical declaration carrying the real compact body; this keeps its
     * symbol id aligned with resolver-created constructor targets and lets the
     * parameter names inside the compact body remain meaningful.
     */
    // 作用：构造记录规范构造器；逻辑：以记录组件生成参数，紧凑构造器时复用其方法体与位置。
    private ConstructorDeclaration recordCanonicalConstructor(
            RecordDeclaration record,
            ConstructorDeclaration compact
    ) {
        ConstructorDeclaration constructor = new ConstructorDeclaration()
                .setName(record.getNameAsString())
                .setBody(compact == null ? new BlockStmt() : compact.getBody().clone());
        for (Parameter component : record.getParameters()) {
            Parameter parameter = new Parameter();
            parameter.setType(component.getType().clone());
            parameter.setName(component.getNameAsString());
            constructor.addParameter(parameter);
        }
        if (compact != null && compact.getRange().isPresent()) {
            constructor.setRange(compact.getRange().orElseThrow());
        } else if (record.getRange().isPresent()) {
            constructor.setRange(record.getRange().orElseThrow());
        }
        return constructor;
    }

    /**
     * Java executes static field initializers and static blocks before the
     * first instance is created, and instance initializers before constructor
     * bodies. They need their own scope so repeated constructors cannot
     * duplicate the same edge and so static calls are not attributed to an
     * unrelated constructor.
     */
    // 作用：补充初始化作用域；逻辑：把字段初始化与静态、实例初始化块分别汇总为独立方法体。
    private void addInitializerScopes(
            SourceFileRecord source,
            String classSymbolId,
            String typeQname,
            TypeDeclaration<?> type,
            List<CallableScope> scopes,
            JavaAnalysisAccumulator accumulator
    ) {
        BlockStmt staticBody = new BlockStmt();
        BlockStmt instanceBody = new BlockStmt();
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof FieldDeclaration field) {
                BlockStmt targetBody = field.isStatic() ? staticBody : instanceBody;
                for (VariableDeclarator variable : field.getVariables()) {
                    variable.getInitializer().ifPresent(initializer ->
                            targetBody.addStatement(fieldInitializerStatement(field, variable, initializer)));
                }
            } else if (member instanceof InitializerDeclaration initializer) {
                BlockStmt targetBody = initializer.isStatic() ? staticBody : instanceBody;
                for (Statement statement : initializer.getBody().getStatements()) {
                    targetBody.addStatement(statement.clone());
                }
            }
        }
        addInitializerScope(source, classSymbolId, typeQname, type, "<clinit>", staticBody, scopes, accumulator);
        addInitializerScope(source, classSymbolId, typeQname, type, "<init-fields>", instanceBody, scopes, accumulator);
    }

    // 作用：把字段初始化转为赋值语句；逻辑：静态字段用类型名限定，实例字段用 this 限定。
    private ExpressionStmt fieldInitializerStatement(
            FieldDeclaration field,
            VariableDeclarator variable,
            Expression initializer
    ) {
        Expression target = field.isStatic()
                ? new FieldAccessExpr(new NameExpr(field.findAncestor(TypeDeclaration.class)
                .map(TypeDeclaration::getNameAsString)
                .orElse("this")), variable.getNameAsString())
                : new FieldAccessExpr(new ThisExpr(), variable.getNameAsString());
        AssignExpr assignment = new AssignExpr(target, initializer.clone(), AssignExpr.Operator.ASSIGN);
        initializer.getRange().ifPresent(assignment::setRange);
        ExpressionStmt statement = new ExpressionStmt(assignment);
        assignment.getRange().ifPresent(statement::setRange);
        return statement;
    }

    // 作用：登记初始化作用域；逻辑：方法体非空时生成合成方法并提取为可调用作用域。
    private void addInitializerScope(
            SourceFileRecord source,
            String classSymbolId,
            String typeQname,
            TypeDeclaration<?> type,
            String name,
            BlockStmt body,
            List<CallableScope> scopes,
            JavaAnalysisAccumulator accumulator
    ) {
        if (body.getStatements().isEmpty()) {
            return;
        }
        MethodDeclaration synthetic = new MethodDeclaration()
                .setName(name)
                .setType("void")
                .setBody(body);
        synthetic.setRange(type.getRange().orElse(null));
        scopes.add(extractCallable(source, classSymbolId, typeQname, synthetic, accumulator));
    }

    // 作用：登记可调用体；逻辑：计算签名与 ID，写入符号记录并返回作用域对象。
    private CallableScope extractCallable(
            SourceFileRecord source,
            String classSymbolId,
            String typeQname,
            CallableDeclaration<?> callable,
            JavaAnalysisAccumulator accumulator
    ) {
        List<String> parameterTypes = JavaSymbolIdentity.parameterTypes(callable);
        String qname = JavaSymbolIdentity.callableQname(typeQname, callable, parameterTypes);
        String symbolId = JavaSymbolIdentity.callableSymbolId(source.moduleId(), qname);
        String kind = JavaSymbolIdentity.callableKind(callable);
        String returnType = JavaSymbolIdentity.returnType(callable);
        String displayName = JavaSymbolIdentity.displayName(callable);
        accumulator.addSymbol(new SymbolRecord(
                symbolId,
                source.fileId(),
                source.moduleId(),
                kind,
                SymbolRoleClassifier.of(callable),
                qname,
                displayName,
                typeQname,
                parameterTypes,
                returnType,
                classSymbolId,
                callable.getBegin().map(position -> position.line).orElse(1),
                callable.getBegin().map(position -> position.column).orElse(1),
                callable.getEnd().map(position -> position.line).orElse(1),
                callable.getEnd().map(position -> position.column).orElse(1)
        ));
        return new CallableScope(
                classSymbolId,
                typeQname,
                symbolId,
                qname,
                displayName,
                parameterTypes,
                returnType,
                callable
        );
    }

    // 作用：构造类型符号；逻辑：写入类型种类、限定名、包名与位置信息。
    private SymbolRecord classSymbol(
            SourceFileRecord source,
            String typeQname,
            String packageName,
            String classSymbolId,
            TypeDeclaration<?> type
    ) {
        return new SymbolRecord(
                classSymbolId,
                source.fileId(),
                source.moduleId(),
                JavaTypeKind.of(type),
                SymbolRoleClassifier.BUSINESS,
                typeQname,
                type.getNameAsString(),
                packageName,
                List.of(),
                null,
                null,
                type.getBegin().map(position -> position.line).orElse(1),
                type.getBegin().map(position -> position.column).orElse(1),
                type.getEnd().map(position -> position.line).orElse(1),
                type.getEnd().map(position -> position.column).orElse(1)
        );
    }
}
