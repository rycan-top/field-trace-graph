package io.fieldtracegraph.javaanalysis.mybatis;

import com.github.javaparser.ast.body.Parameter;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 作用：把 SQL 中的 #{} 占位符解析到 Java 字段。
// 使用场景：Mapper 接口参数与 SQL 参数绑定分析。

/**
 * Maps MyBatis parameter expressions such as {@code bidId} or
 * {@code item.bidId} to the owning Java method parameter.
 */
public final class MyBatisParameterResolver {
    private static final Set<String> SPECIAL_COLLECTION_ALIASES = Set.of(
            "list", "array", "collection"
    );
    private static final Pattern OGNL_IDENTIFIER = Pattern.compile(
            "[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*"
    );
    private static final Set<String> OGNL_KEYWORDS = Set.of(
            "and", "or", "not", "eq", "ne", "lt", "le", "gt", "ge",
            "true", "false", "null", "new", "instanceof"
    );

    // 作用：解析带 foreach 与 bind 别名的参数路径；逻辑：初始化已访问集合后转入核心解析。
    public String valuePath(
            String expression,
            List<Parameter> parameters,
            List<MyBatisMapperDefinition.ForeachAlias> aliases,
            List<MyBatisMapperDefinition.BindAlias> bindAliases
    ) {
        return valuePath(
                expression,
                parameters,
                aliases,
                bindAliases,
                new LinkedHashSet<>()
        );
    }

    // 作用：递归解析参数表达式；逻辑：依次处理 bind 别名、foreach 项、显式参数名、特殊集合别名与 param 下标。
    private String valuePath(
            String expression,
            List<Parameter> parameters,
            List<MyBatisMapperDefinition.ForeachAlias> aliases,
            List<MyBatisMapperDefinition.BindAlias> bindAliases,
            Set<String> visitedBinds
    ) {
        if (expression == null || expression.isBlank() || parameters.isEmpty()) {
            return null;
        }
        String root = expression;
        int dot = expression.indexOf('.');
        if (dot > 0) {
            root = expression.substring(0, dot);
        }
        String bindPath = bindPath(
                expression,
                root,
                parameters,
                aliases,
                bindAliases,
                visitedBinds
        );
        if (bindPath != null) {
            return bindPath;
        }
        Map<String, String> collectionByAlias = new LinkedHashMap<>();
        for (MyBatisMapperDefinition.ForeachAlias alias : aliases) {
            collectionByAlias.putIfAbsent(alias.item(), alias.collection());
        }
        String collection = collectionByAlias.get(root);
        if (collection != null) {
            String collectionPath = valuePath(
                    collection,
                    parameters,
                    List.of(),
                    bindAliases,
                    visitedBinds
            );
            if (collectionPath == null) {
                return null;
            }
            String suffix = expression.length() == root.length()
                    ? ""
                    : expression.substring(root.length());
            return collectionPath + "[*]" + suffix;
        }
        final String expectedRoot = root;
        for (Parameter parameter : parameters) {
            if (parameter.getNameAsString().equals(expectedRoot)) {
                return expression;
            }
            if (annotationParamName(parameter).filter(expectedRoot::equals).isPresent()) {
                String suffix = expression.length() == root.length()
                        ? ""
                        : expression.substring(root.length());
                return parameter.getNameAsString() + suffix;
            }
        }
        String specialAlias = specialCollectionAlias(expression);
        if (specialAlias != null && parameters.size() == 1) {
            String parameterName = parameters.get(0).getNameAsString();
            String suffix = expression.substring(specialAlias.length());
            suffix = suffix.replaceFirst("^\\[[0-9]+\\]", "[*]");
            return parameterName + suffix;
        }
        if ("_parameter".equals(expectedRoot)) {
            return parameters.size() == 1
                    ? parameters.get(0).getNameAsString()
                    : expression;
        }
        Integer indexed = indexedParameter(expectedRoot);
        if (indexed != null && indexed >= 0 && indexed < parameters.size()) {
            String parameterName = parameters.get(indexed).getNameAsString();
            String suffix = expression.length() == root.length()
                    ? ""
                    : expression.substring(root.length());
            return parameterName + suffix;
        }
        if (parameters.size() == 1) {
            String parameterName = parameterName(parameters.get(0));
            return parameterName + "." + expression;
        }
        return null;
    }

    // 作用：解析 bind 别名指向的参数路径；逻辑：找到同名别名后递归解析其值并拼接后续字段后缀。
    private String bindPath(
            String expression,
            String root,
            List<Parameter> parameters,
            List<MyBatisMapperDefinition.ForeachAlias> foreachAliases,
            List<MyBatisMapperDefinition.BindAlias> bindAliases,
            Set<String> visitedBinds
    ) {
        MyBatisMapperDefinition.BindAlias bind = bindAliases.stream()
                .filter(alias -> alias.name().equals(root))
                .findFirst()
                .orElse(null);
        if (bind == null || !visitedBinds.add(root)) {
            return null;
        }
        String resolved = resolveBindValue(
                bind.value(),
                parameters,
                foreachAliases,
                bindAliases,
                visitedBinds
        );
        if (resolved == null) {
            return null;
        }
        String suffix = expression.length() == root.length()
                ? ""
                : expression.substring(root.length());
        return resolved + suffix;
    }

    // 作用：解析 bind 表达式的值来源；逻辑：先尝试整体标识符，再扫描 OGNL 表达式中的有效标识符。
    private String resolveBindValue(
            String value,
            List<Parameter> parameters,
            List<MyBatisMapperDefinition.ForeachAlias> foreachAliases,
            List<MyBatisMapperDefinition.BindAlias> bindAliases,
            Set<String> visitedBinds
    ) {
        String direct = value == null ? "" : value.trim();
        if (OGNL_IDENTIFIER.matcher(direct).matches()) {
            String resolved = valuePath(
                    direct,
                    parameters,
                    foreachAliases,
                    bindAliases,
                    visitedBinds
            );
            if (resolved != null) {
                return resolved;
            }
        }
        Matcher matcher = OGNL_IDENTIFIER.matcher(direct);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (OGNL_KEYWORDS.contains(candidate)) {
                continue;
            }
            String resolved = valuePath(
                    candidate,
                    parameters,
                    foreachAliases,
                    bindAliases,
                    visitedBinds
            );
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    // 作用：识别 MyBatis 特殊集合别名；逻辑：匹配 list、array、collection 及其字段或下标形式。
    private String specialCollectionAlias(String expression) {
        for (String alias : SPECIAL_COLLECTION_ALIASES) {
            if (expression.equals(alias) || expression.startsWith(alias + ".")
                    || expression.startsWith(alias + "[")) {
                return alias;
            }
        }
        return null;
    }

    // 作用：解析 paramN 或 argN 下标参数；逻辑：param 从 1 开始偏移，arg 从 0 开始偏移。
    private Integer indexedParameter(String root) {
        if (root.startsWith("param")) {
            return parseIndex(root.substring("param".length()), 1);
        }
        if (root.startsWith("arg")) {
            return parseIndex(root.substring("arg".length()), 0);
        }
        return null;
    }

    // 作用：把下标文本转换为零基参数位置；逻辑：解析整数后减去偏移，非法数字返回空。
    private Integer parseIndex(String value, int offset) {
        try {
            int index = Integer.parseInt(value);
            return index - offset;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // 作用：取参数的对外名称；逻辑：优先使用 @Param 注解值，否则使用源码参数名。
    public String parameterName(Parameter parameter) {
        return annotationParamName(parameter).orElseGet(parameter::getNameAsString);
    }

    // 作用：读取参数上的 @Param 名称；逻辑：兼容普通注解与单值注解，并去掉字符串字面量引号。
    private java.util.Optional<String> annotationParamName(Parameter parameter) {
        for (var annotation : parameter.getAnnotations()) {
            String annotationName = annotation.getNameAsString();
            if (!("Param".equals(annotationName) || annotationName.endsWith(".Param"))) {
                continue;
            }
            if (annotation.isNormalAnnotationExpr()) {
                var pair = annotation.asNormalAnnotationExpr().getPairs().stream()
                        .filter(candidate -> "value".equals(candidate.getNameAsString()))
                        .findFirst()
                        .orElse(null);
                if (pair != null) {
                    String value = pair.getValue().toString();
                    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                        return java.util.Optional.of(value.substring(1, value.length() - 1));
                    }
                }
            } else if (annotation.isSingleMemberAnnotationExpr()) {
                String value = annotation.asSingleMemberAnnotationExpr().getMemberValue().toString();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    return java.util.Optional.of(value.substring(1, value.length() - 1));
                }
            }
        }
        return java.util.Optional.empty();
    }
}
