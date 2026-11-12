package io.fieldtracegraph.javaanalysis.resolution;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 作用：记录外部类构造器的字段写入关系。
// 使用场景：分析三方库对象构造时补全字段来源。

/**
 * Resolves field bindings for constructors that are present on the validated
 * project classpath but not in the source snapshot. It intentionally uses
 * only metadata that proves a parameter-to-field relationship: record
 * components or constructor parameter names retained in the class file.
 */
public final class ExternalConstructorFieldCatalog {
    private final ClassLoader classLoader;
    private final Map<String, Class<?>> classes = new HashMap<>();

    // 作用：创建外部构造器字段目录；逻辑：保存加载外部类型的类加载器并初始化类缓存。
    public ExternalConstructorFieldCatalog(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    // 作用：按实参类型选择唯一构造器并推导字段绑定；逻辑：先按参数数量筛选，再用类型和可证明的绑定关系消歧。
    public Map<Integer, String> constructorFieldBindings(
            ObjectCreationExpr creation,
            List<String> argumentTypes
    ) {
        String typeName = resolveTypeName(creation);
        if (typeName == null) {
            return Map.of();
        }
        Class<?> type = load(typeName);
        if (type == null) {
            return Map.of();
        }
        try {
            return constructorFieldBindings(type, creation, argumentTypes);
        } catch (LinkageError | RuntimeException e) {
            // 外部类型的字段或构造器可能引用当前 classpath 缺失的类型，反射会抛 LinkageError；
            // 此时放弃字节码级绑定，让源码证据继续生效，而不是让整次索引失败。
            return Map.of();
        }
    }

    // 作用：在已加载类型上完成构造器筛选与字段绑定；逻辑：按参数数量筛选，再用实参类型与可证明的绑定关系消歧。
    private Map<Integer, String> constructorFieldBindings(
            Class<?> type,
            ObjectCreationExpr creation,
            List<String> argumentTypes
    ) {
        List<Constructor<?>> candidates = new ArrayList<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == creation.getArguments().size()) {
                candidates.add(constructor);
            }
        }
        if (candidates.size() > 1 && !argumentTypes.isEmpty()) {
            candidates = candidates.stream()
                    .filter(constructor -> parametersMatch(constructor.getParameterTypes(), argumentTypes))
                    .toList();
        }
        if (candidates.size() > 1) {
            candidates = candidates.stream()
                    .filter(constructor -> !bindings(type, constructor).isEmpty())
                    .toList();
        }
        if (candidates.size() != 1) {
            return Map.of();
        }
        return bindings(type, candidates.get(0));
    }

    // 作用：比较构造器形参与调用实参类型；逻辑：逐个按简单名和装箱类型匹配，未知实参类型视为可接受。
    private boolean parametersMatch(Class<?>[] parameterTypes, List<String> argumentTypes) {
        if (parameterTypes.length != argumentTypes.size()) {
            return false;
        }
        for (int index = 0; index < parameterTypes.length; index++) {
            String argument = simpleName(argumentTypes.get(index));
            if (argument == null || argument.isBlank()) {
                continue;
            }
            String parameter = simpleName(parameterTypes[index].getTypeName());
            if (!parameter.equals(argument)
                    && !boxed(parameter).equals(argument)
                    && !parameter.equals(boxed(argument))) {
                return false;
            }
        }
        return true;
    }

    // 作用：提取类型简单名；逻辑：去掉泛型参数与包名前缀后返回末段名称。
    private String simpleName(String typeName) {
        if (typeName == null) {
            return null;
        }
        String result = typeName.trim();
        int generic = result.indexOf('<');
        if (generic >= 0) {
            result = result.substring(0, generic);
        }
        int dot = result.lastIndexOf('.');
        return dot < 0 ? result : result.substring(dot + 1);
    }

    // 作用：把原始类型映射为包装类型；逻辑：按内置映射表转换，非原始类型原样返回。
    private String boxed(String typeName) {
        return switch (typeName) {
            case "int" -> "Integer";
            case "long" -> "Long";
            case "double" -> "Double";
            case "float" -> "Float";
            case "short" -> "Short";
            case "byte" -> "Byte";
            case "char" -> "Character";
            case "boolean" -> "Boolean";
            default -> typeName;
        };
    }

    // 作用：推导构造器参数对应的字段；逻辑：记录类型按组件顺序绑定，普通类要求参数名保留且字段存在。
    private Map<Integer, String> bindings(Class<?> type, Constructor<?> constructor) {
        Map<String, String> fieldNames = fieldNames(type);
        Map<Integer, String> result = new LinkedHashMap<>();
        if (type.isRecord()) {
            RecordComponent[] components = type.getRecordComponents();
            for (int index = 0; index < components.length && index < constructor.getParameterCount(); index++) {
                if (fieldNames.containsKey(components[index].getName())) {
                    result.put(index, components[index].getName());
                }
            }
            return Map.copyOf(result);
        }
        Parameter[] parameters = constructor.getParameters();
        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            if (!parameter.isNamePresent()) {
                return Map.of();
            }
            String fieldName = fieldNames.get(parameter.getName());
            if (fieldName == null) {
                return Map.of();
            }
            result.put(index, fieldName);
        }
        return Map.copyOf(result);
    }

    // 作用：收集类型继承链上的实例字段名；逻辑：逐级检查声明字段，跳过合成字段与静态字段。
    private Map<String, String> fieldNames(Class<?> type) {
        Map<String, String> result = new HashMap<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.isSynthetic() && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    result.putIfAbsent(field.getName(), field.getName());
                }
            }
        }
        return Map.copyOf(result);
    }

    // 作用：按名称加载外部类；逻辑：命中缓存直接返回，未命中时尝试加载并缓存成功或失败结果。
    private Class<?> load(String typeName) {
        if (classes.containsKey(typeName)) {
            return classes.get(typeName);
        }
        try {
            Class<?> type = Class.forName(typeName, false, classLoader);
            classes.put(typeName, type);
            return type;
        } catch (ClassNotFoundException | LinkageError ignored) {
            classes.put(typeName, null);
            return null;
        }
    }

    // 作用：解析构造表达式的全限定类型名；逻辑：优先全名，再按显式导入、同包、通配导入和 java.lang 依次探测。
    private String resolveTypeName(ObjectCreationExpr creation) {
        String value = JavaTypeNameResolver.normalize(creation.getType().asString());
        if (value == null || value.isBlank()) {
            return null;
        }
        CompilationUnit unit = creation.findCompilationUnit().orElse(null);
        if (value.contains(".")) {
            return value;
        }
        if (unit != null) {
            for (var declaration : unit.getImports()) {
                if (!declaration.isStatic() && !declaration.isAsterisk()
                        && declaration.getNameAsString().endsWith("." + value)) {
                    return declaration.getNameAsString();
                }
            }
            String packageName = unit.getPackageDeclaration()
                    .map(declaration -> declaration.getNameAsString())
                    .orElse("");
            String samePackage = packageName.isBlank() ? value : packageName + "." + value;
            if (load(samePackage) != null) {
                return samePackage;
            }
            for (var declaration : unit.getImports()) {
                if (declaration.isAsterisk() && !declaration.isStatic()) {
                    String candidate = declaration.getNameAsString() + "." + value;
                    if (load(candidate) != null) {
                        return candidate;
                    }
                }
            }
        }
        String javaLang = "java.lang." + value;
        return load(javaLang) == null ? null : javaLang;
    }
}
