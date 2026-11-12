package io.fieldtracegraph.javaanalysis.resolution;

import com.github.javaparser.ast.CompilationUnit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：规范化 Java 类型名。
// 使用场景：解析内部类、全限定名与 import 引用。

/**
 * Resolves a type name as it is written inside one compilation unit into the
 * fully qualified name known by {@link SourceTypeCatalog}.
 *
 * <p>Only name resolution lives here: import lookup, same-package lookup,
 * wildcard import lookup and finally the catalog's global simple-name index.
 * Callers keep responsibility for deciding what the resolved type means.</p>
 */
public final class JavaTypeNameResolver {
    /**
     * Names that resolve from {@code java.lang} without an import. Only this
     * bounded set is treated as an implicit platform type; an unknown
     * capitalized name is not silently attributed to {@code java.lang}.
     */
    private static final Set<String> IMPLICIT_JAVA_LANG = Set.of(
            "AutoCloseable",
            "Boolean",
            "Byte",
            "CharSequence",
            "Character",
            "Class",
            "Cloneable",
            "Comparable",
            "Double",
            "Enum",
            "Error",
            "Exception",
            "Float",
            "FunctionalInterface",
            "Integer",
            "Iterable",
            "Long",
            "Math",
            "Number",
            "Object",
            "Override",
            "Record",
            "RuntimeException",
            "Short",
            "String",
            "StringBuffer",
            "StringBuilder",
            "SuppressWarnings",
            "System",
            "Thread",
            "Throwable",
            "Void"
    );

    private final SourceTypeCatalog catalog;

    // 作用：创建类型名解析器；逻辑：保存用于查询全限定名的类型目录。
    public JavaTypeNameResolver(SourceTypeCatalog catalog) {
        this.catalog = catalog;
    }

    // 作用：把源码中的类型名解析为全限定名；逻辑：按全名、显式导入、同包、通配导入与全局简单名索引依次查找。
    public String resolve(CompilationUnit unit, String typeName) {
        String normalized = normalize(typeName);
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        if (normalized.contains(".")) {
            return catalog.type(normalized) != null ? normalized : null;
        }
        ImportContext imports = ImportContext.of(unit);
        String explicit = imports.explicitImports().get(normalized);
        if (explicit != null) {
            return catalog.type(explicit) != null ? explicit : null;
        }
        String samePackage = imports.packageName().isEmpty()
                ? normalized
                : imports.packageName() + "." + normalized;
        if (catalog.type(samePackage) != null) {
            return samePackage;
        }
        for (String wildcard : imports.wildcardImports()) {
            String candidate = wildcard + "." + normalized;
            if (catalog.type(candidate) != null) {
                return candidate;
            }
        }
        return catalog.resolveTypeName(normalized);
    }

    /**
     * Candidate fully qualified names for a type that is not part of the
     * analysed source set. Imports are trustworthy syntax, so an explicit
     * import is enough to name an external type even when no classpath entry
     * confirms its members. The {@code java.lang} fallback stays bounded to
     * the well-known implicit names.
     */
    // 作用：给出外部类型的候选全名；逻辑：收集显式导入、通配导入与受限的 java.lang 隐式名称，不强行断言存在。
    public List<String> externalCandidates(CompilationUnit unit, String typeName) {
        String normalized = normalize(typeName);
        if (normalized == null || normalized.isEmpty()) {
            return List.of();
        }
        if (normalized.contains(".")) {
            return List.of(normalized);
        }
        ImportContext imports = ImportContext.of(unit);
        List<String> result = new ArrayList<>();
        String explicit = imports.explicitImports().get(normalized);
        if (explicit != null && catalog.type(explicit) == null) {
            result.add(explicit);
        }
        for (String wildcard : imports.wildcardImports()) {
            if (wildcard.equals(imports.packageName())) {
                continue;
            }
            String candidate = wildcard + "." + normalized;
            if (catalog.type(candidate) == null && !result.contains(candidate)) {
                result.add(candidate);
            }
        }
        if (IMPLICIT_JAVA_LANG.contains(normalized)) {
            String candidate = "java.lang." + normalized;
            if (!result.contains(candidate)) {
                result.add(candidate);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Strips generic arguments, arrays and varargs so a declaration such as
     * {@code List<AgreementSignBO>} still yields a catalog type name.
     */
    // 作用：规范化类型声明文本；逻辑：去掉泛型参数、数组维度与可变参数后缀，返回干净的类型名。
    public static String normalize(String typeName) {
        if (typeName == null) {
            return null;
        }
        String result = typeName.trim();
        int genericStart = result.indexOf('<');
        if (genericStart >= 0) {
            result = result.substring(0, genericStart);
        }
        while (result.endsWith("[]")) {
            result = result.substring(0, result.length() - 2);
        }
        if (result.endsWith("...")) {
            result = result.substring(0, result.length() - 3);
        }
        return result.trim();
    }

    private record ImportContext(String packageName, Map<String, String> explicitImports, List<String> wildcardImports) {
        // 作用：提取编译单元的导入信息；逻辑：读取包名，分别收集非静态显式导入与通配导入。
        static ImportContext of(CompilationUnit unit) {
            String packageName = unit.getPackageDeclaration()
                    .map(declaration -> declaration.getNameAsString())
                    .orElse("");
            Map<String, String> explicit = new HashMap<>();
            List<String> wildcards = new ArrayList<>();
            for (var importDeclaration : unit.getImports()) {
                if (importDeclaration.isAsterisk()) {
                    wildcards.add(importDeclaration.getNameAsString());
                } else if (!importDeclaration.isStatic()) {
                    String qname = importDeclaration.getNameAsString();
                    String simpleName = qname.substring(qname.lastIndexOf('.') + 1);
                    explicit.put(simpleName, qname);
                }
            }
            return new ImportContext(packageName, Map.copyOf(explicit), List.copyOf(wildcards));
        }
    }
}
