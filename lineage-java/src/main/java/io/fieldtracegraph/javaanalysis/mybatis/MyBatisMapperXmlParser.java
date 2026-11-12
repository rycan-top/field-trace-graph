package io.fieldtracegraph.javaanalysis.mybatis;

import io.fieldtracegraph.core.model.Diagnostic;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 作用：解析 MyBatis Mapper XML。
// 使用场景：处理 select、insert、update、delete 以及动态 SQL 标签。

/**
 * Reads MyBatis mapper XML without resolving external DTDs or entities.
 * DOCTYPE is accepted because MyBatis mapper files commonly declare it.
 *
 * <p>The parser keeps source offsets for every bind variable and expands
 * {@code <include>} fragments for parameter discovery. This is deliberately
 * still static: it never executes MyBatis or reads runtime configuration.</p>
 */
public final class MyBatisMapperXmlParser {
    private static final Pattern PARAMETER = Pattern.compile("([#$])\\{\\s*([^,}\\s]+)");
    private static final Pattern IBATIS_PARAMETER = Pattern.compile("([#$])([^#$\\s{]+)\\1");
    private static final Pattern MAPPER_TAG = Pattern.compile("<\\s*mapper(?:\\s|>)");
    private static final Pattern SQLMAP_TAG = Pattern.compile("<\\s*sqlMap(?:\\s|>)");
    private static final Set<String> STATEMENT_TAGS = Set.of(
            "select", "insert", "update", "delete", "statement"
    );

    // 作用：安全解析 Mapper XML 并生成定义；逻辑：识别 MyBatis/iBatis、关闭外部实体、收集语句、片段、动态别名与结果映射。
    public MyBatisMapperDefinition parse(
            Path projectRoot,
            Path xmlPath,
            Consumer<Diagnostic> diagnostics
    ) {
        String relativePath = projectRoot.relativize(xmlPath).toString().replace('\\', '/');
        try {
            String source = readText(xmlPath);
            boolean myBatis = MAPPER_TAG.matcher(source).find();
            boolean iBatis = SQLMAP_TAG.matcher(source).find();
            if (!myBatis && !iBatis) {
                return null;
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                // 作用：处理 XML 警告；逻辑：把警告也作为解析失败抛出，避免静默接受异常文档。
                public void warning(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                // 作用：处理 XML 错误；逻辑：直接抛出异常并终止解析。
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                // 作用：处理 XML 致命错误；逻辑：直接抛出异常并交由外层统一记录诊断。
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            Document document = builder.parse(
                    new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8))
            );
            Element mapper = document.getDocumentElement();
            if (mapper == null
                    || (!"mapper".equals(mapper.getTagName())
                    && !"sqlMap".equals(mapper.getTagName()))) {
                diagnostics.accept(new Diagnostic(
                        "MYBATIS_MAPPER_INVALID",
                        "Mapper XML root element must be <mapper> or <sqlMap>",
                        relativePath,
                        1,
                        false,
                        false
                ));
                return null;
            }
            String namespace = mapper.getAttribute("namespace");
            if (namespace.isBlank()) {
                diagnostics.accept(new Diagnostic(
                        "MYBATIS_MAPPER_INVALID",
                        "Mapper XML namespace is required",
                        relativePath,
                        1,
                        false,
                        false
                ));
                return null;
            }
            Map<String, Element> fragments = new LinkedHashMap<>();
            Map<String, Element> resultMaps = new LinkedHashMap<>();
            List<Element> statements = new ArrayList<>();
            NodeList children = mapper.getChildNodes();
            for (int index = 0; index < children.getLength(); index++) {
                Node node = children.item(index);
                if (node.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element element = (Element) node;
                if ("sql".equals(element.getTagName()) && !element.getAttribute("id").isBlank()) {
                    fragments.put(element.getAttribute("id"), element);
                } else if ("resultMap".equals(element.getTagName())
                        && !element.getAttribute("id").isBlank()) {
                    resultMaps.put(element.getAttribute("id"), element);
                } else if (STATEMENT_TAGS.contains(element.getTagName())
                        && !element.getAttribute("id").isBlank()) {
                    statements.add(element);
                }
            }
            Map<String, Range> fragmentRanges = new LinkedHashMap<>();
            for (Map.Entry<String, Element> entry : fragments.entrySet()) {
                Range range = elementRange(source, entry.getValue(), 0);
                if (range != null) {
                    fragmentRanges.put(entry.getKey(), range);
                }
            }
            List<MyBatisMapperDefinition.Statement> result = new ArrayList<>();
            int searchFrom = 0;
            for (Element statement : statements) {
                String id = statement.getAttribute("id");
                Range range = elementRange(source, statement, searchFrom);
                if (range == null) {
                    continue;
                }
                searchFrom = range.end();
                List<MyBatisMapperDefinition.ParameterReference> parameters =
                        new ArrayList<>(parametersInRange(source, range.start(), range.end()));
                Set<String> visitedFragments = new HashSet<>();
                collectIncludedParameters(
                        statement,
                        source,
                        fragments,
                        fragmentRanges,
                        visitedFragments,
                        parameters
                );
                List<MyBatisMapperDefinition.ForeachAlias> aliases = new ArrayList<>();
                collectForeachAliases(
                        statement,
                        source,
                        range,
                        fragments,
                        fragmentRanges,
                        new HashSet<>(),
                        aliases
                );
                List<MyBatisMapperDefinition.BindAlias> bindAliases = new ArrayList<>();
                collectBindAliases(
                        statement,
                        source,
                        range,
                        fragments,
                        fragmentRanges,
                        new HashSet<>(),
                        bindAliases
                );
                String resultMap = statement.getAttribute("resultMap");
                String resultType = statement.getAttribute("resultType");
                if (resultType.isBlank()) {
                    resultType = statement.getAttribute("resultClass");
                }
                List<MyBatisMapperDefinition.ResultMapping> resultMappings =
                        collectResultMappings(
                                resultMap,
                                source,
                                resultMaps,
                                namespace
                        );
                if (resultMappings.isEmpty() && !resultType.isBlank()) {
                    resultMappings = inferResultTypeMappings(
                            source,
                            range,
                            resultType
                    );
                }
                result.add(new MyBatisMapperDefinition.Statement(
                        id,
                        position(source, range.start()).line(),
                        parameterType(statement),
                        dedupe(parameters),
                        List.copyOf(aliases),
                        resultMap,
                        resultType,
                        List.copyOf(resultMappings),
                        List.copyOf(bindAliases)
                ));
            }
            return new MyBatisMapperDefinition(
                    namespace,
                    xmlPath,
                    relativePath,
                    iBatis ? "iBatis" : "MyBatis",
                    List.copyOf(result)
            );
        } catch (Exception failure) {
            Integer line = failure instanceof SAXParseException sax
                    && sax.getLineNumber() > 0
                    ? sax.getLineNumber()
                    : null;
            diagnostics.accept(new Diagnostic(
                    "MYBATIS_XML_PARSE_FAILED",
                    failure.getMessage() == null
                            ? failure.getClass().getSimpleName()
                            : failure.getMessage(),
                    relativePath,
                    line,
                    false,
                    false
            ));
            return null;
        }
    }

    // 作用：递归收集 include 片段中的参数；逻辑：遇到 include 先扫描片段范围，再递归进入片段元素并防止循环引用。
    private void collectIncludedParameters(
            Element element,
            String source,
            Map<String, Element> fragments,
            Map<String, Range> fragmentRanges,
            Set<String> visitedFragments,
            List<MyBatisMapperDefinition.ParameterReference> parameters
    ) {
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element child = (Element) node;
            if ("include".equals(child.getTagName())) {
                String refid = child.getAttribute("refid");
                if (refid.isBlank() || !visitedFragments.add(refid)) {
                    continue;
                }
                Range fragment = fragmentRanges.get(refid);
                if (fragment != null) {
                    parameters.addAll(parametersInRange(
                            source,
                            fragment.start(),
                            fragment.end()
                    ));
                }
                Element fragmentElement = fragments.get(refid);
                if (fragmentElement != null) {
                    collectIncludedParameters(
                            fragmentElement,
                            source,
                            fragments,
                            fragmentRanges,
                            visitedFragments,
                            parameters
                    );
                }
            } else {
                collectIncludedParameters(
                        child,
                        source,
                        fragments,
                        fragmentRanges,
                        visitedFragments,
                        parameters
                );
            }
        }
    }

    // 作用：提取指定源码范围内的占位符参数；逻辑：分别匹配 MyBatis 与 iBatis 语法，按位置去重后返回。
    private List<MyBatisMapperDefinition.ParameterReference> parametersInRange(
            String source,
            int start,
            int end
    ) {
        if (start < 0 || end <= start || end > source.length()) {
            return List.of();
        }
        Map<String, MyBatisMapperDefinition.ParameterReference> result = new LinkedHashMap<>();
        collectParameters(source, start, end, PARAMETER, true, result);
        collectParameters(source, start, end, IBATIS_PARAMETER, false, result);
        return List.copyOf(result.values());
    }

    // 作用：用单个正则收集参数引用；逻辑：记录表达式、起止行列、语法标记以及是否位于动态标签内。
    private void collectParameters(
            String source,
            int start,
            int end,
            Pattern pattern,
            boolean myBatis,
            Map<String, MyBatisMapperDefinition.ParameterReference> result
    ) {
        Matcher matcher = pattern.matcher(source.substring(start, end));
        while (matcher.find()) {
            String expression = matcher.group(2).trim();
            if (expression.isBlank()) {
                continue;
            }
            int expressionStart = start + matcher.start(2);
            String key = expression + "|" + expressionStart;
            if (result.containsKey(key)) {
                continue;
            }
            int expressionEnd = start + matcher.end(2);
            Position begin = position(source, expressionStart);
            Position finish = position(source, expressionEnd);
            result.put(key, new MyBatisMapperDefinition.ParameterReference(
                    expression,
                    begin.line(),
                    begin.column(),
                    finish.line(),
                    finish.column(),
                    myBatis ? matcher.group(1) + "{" : matcher.group(1),
                    insideDynamicTag(source, start, expressionStart)
            ));
        }
    }

    private static final java.util.List<String> DYNAMIC_TAGS = java.util.List.of(
            "if", "choose", "when", "otherwise", "trim", "where", "set", "foreach"
    );

    /**
     * A bind inside {@code <if>}/{@code <choose>}/{@code <foreach>} only
     * executes for some inputs. The static graph keeps the possible edge, but
     * marks it as candidate evidence until a runtime SQL capture confirms the
     * statement that actually ran.
     */
    // 作用：判断占位符是否位于动态 SQL 标签内；逻辑：统计当前位置之前各动态标签的开闭数量，存在未闭合标签即成立。
    private boolean insideDynamicTag(String source, int start, int position) {
        String prefix = source.substring(start, position);
        for (String tag : DYNAMIC_TAGS) {
            int open = count(prefix, "<" + tag);
            int close = count(prefix, "</" + tag + ">");
            if (open > close) {
                return true;
            }
        }
        return false;
    }

    // 作用：统计完整标签文本出现次数；逻辑：查找目标串并跳过后面紧跟标识符字符的伪匹配。
    private int count(String value, String needle) {
        int total = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            int after = index + needle.length();
            if (after >= value.length() || !Character.isLetterOrDigit(value.charAt(after))) {
                total++;
            }
            index = after;
        }
        return total;
    }

    // 作用：读取语句的参数类型属性；逻辑：优先 MyBatis 的 parameterType，缺失时回退 iBatis 的 parameterClass。
    private String parameterType(Element statement) {
        String value = statement.getAttribute("parameterType");
        return value.isBlank() ? statement.getAttribute("parameterClass") : value;
    }

    // 作用：解析语句引用的结果映射；逻辑：先定位 resultMap，再递归收集字段并初始化访问集合与位置游标。
    private List<MyBatisMapperDefinition.ResultMapping> collectResultMappings(
            String resultMapId,
            String source,
            Map<String, Element> resultMaps,
            String namespace
    ) {
        if (resultMapId == null || resultMapId.isBlank()) {
            return List.of();
        }
        Element resultMap = resultMap(resultMapId, resultMaps, namespace);
        if (resultMap == null) {
            return List.of();
        }
        List<MyBatisMapperDefinition.ResultMapping> result = new ArrayList<>();
        collectResultMappings(
                resultMap,
                "",
                source,
                resultMaps,
                namespace,
                result,
                new HashSet<>(),
                new int[]{0}
        );
        return List.copyOf(result);
    }

    // 作用：递归展开结果映射树；逻辑：处理 result/id/arg，关联对象与集合会沿引用映射和嵌套节点继续收集。
    private void collectResultMappings(
            Element container,
            String propertyPrefix,
            String source,
            Map<String, Element> resultMaps,
            String namespace,
            List<MyBatisMapperDefinition.ResultMapping> result,
            Set<String> visitedResultMaps,
            int[] cursor
    ) {
        NodeList children = container.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element child = (Element) node;
            Range range = elementRange(source, child, cursor[0]);
            if (range == null) {
                continue;
            }
            cursor[0] = range.end();
            String tag = child.getTagName();
            if ("result".equals(tag) || "id".equals(tag) || "arg".equals(tag)) {
                String column = child.getAttribute("column");
                String property = child.getAttribute("property");
                if (column.isBlank() || property.isBlank()) {
                    continue;
                }
                Position position = position(source, range.start());
                result.add(new MyBatisMapperDefinition.ResultMapping(
                        column,
                        joinProperty(propertyPrefix, property),
                        position.line(),
                        position.column()
                ));
                continue;
            }
            if (!"association".equals(tag) && !"collection".equals(tag)) {
                continue;
            }
            String prefix = joinProperty(propertyPrefix, child.getAttribute("property"));
            String referenced = child.getAttribute("resultMap");
            if (!referenced.isBlank()) {
                Element mapped = resultMap(referenced, resultMaps, namespace);
                String identity = mapped == null ? referenced : mapped.getAttribute("id");
                if (mapped != null && visitedResultMaps.add(identity)) {
                    collectResultMappings(
                            mapped,
                            prefix,
                            source,
                            resultMaps,
                            namespace,
                            result,
                            visitedResultMaps,
                            new int[]{0}
                    );
                }
            }
            collectResultMappings(
                    child,
                    prefix,
                    source,
                    resultMaps,
                    namespace,
                    result,
                    visitedResultMaps,
                    new int[]{range.start()}
            );
        }
    }

    // 作用：按 ID 查找结果映射；逻辑：依次尝试原 ID、去命名空间后的简单名以及当前命名空间限定名。
    private Element resultMap(
            String resultMapId,
            Map<String, Element> resultMaps,
            String namespace
    ) {
        Element resultMap = resultMaps.get(resultMapId);
        if (resultMap == null && resultMapId.contains(".")) {
            resultMap = resultMaps.get(resultMapId.substring(resultMapId.lastIndexOf('.') + 1));
        }
        if (resultMap == null) {
            String simple = resultMapId.contains(".")
                    ? resultMapId.substring(resultMapId.lastIndexOf('.') + 1)
                    : resultMapId;
            resultMap = resultMaps.get(namespace + "." + simple);
        }
        return resultMap;
    }

    // 作用：拼接嵌套属性路径；逻辑：空属性返回前缀，空前缀返回属性，否则以点号连接。
    private String joinProperty(String prefix, String property) {
        if (property == null || property.isBlank()) {
            return prefix == null ? "" : prefix;
        }
        return prefix == null || prefix.isBlank() ? property : prefix + "." + property;
    }

    // 作用：从 select 列表推断结果列映射；逻辑：解析顶层 select 与 from 之间的表达式，结合别名生成候选映射。
    private List<MyBatisMapperDefinition.ResultMapping> inferResultTypeMappings(
            String source,
            Range statementRange,
            String resultType
    ) {
        if (isScalarResultType(resultType)) {
            return List.of();
        }
        String sql = source.substring(statementRange.start(), statementRange.end());
        int tagEnd = sql.indexOf('>');
        if (tagEnd < 0) {
            return List.of();
        }
        int select = keyword(sql, "select", tagEnd + 1);
        if (select < 0) {
            return List.of();
        }
        int selectListStart = select + "select".length();
        int from = keywordAtTopLevel(sql, "from", selectListStart);
        if (from < 0) {
            return List.of();
        }
        String selectList = sql.substring(selectListStart, from);
        List<MyBatisMapperDefinition.ResultMapping> result = new ArrayList<>();
        for (Range item : splitSelectList(selectList)) {
            String rawExpression = selectList.substring(item.start(), item.end());
            String expression = rawExpression.trim();
            if (expression.isBlank()
                    || expression.equals("*")
                    || expression.endsWith(".*")
                    || expression.startsWith("<")) {
                continue;
            }
            int expressionOffset = item.start() + rawExpression.indexOf(expression);
            if (expression.regionMatches(true, 0, "distinct ", 0, "distinct ".length())) {
                String withoutDistinct = expression.substring("distinct ".length());
                String trimmed = withoutDistinct.trim();
                expressionOffset += expression.indexOf(trimmed);
                expression = trimmed;
            }
            Alias alias = sqlAlias(expression);
            if (alias == null || alias.column().isBlank()) {
                continue;
            }
            int offset = statementRange.start() + selectListStart + expressionOffset
                    + alias.offset();
            Position position = position(source, offset);
            result.add(new MyBatisMapperDefinition.ResultMapping(
                    alias.column(),
                    alias.property(),
                    position.line(),
                    position.column(),
                    true
            ));
        }
        return List.copyOf(result);
    }

    // 作用：判断结果类型是否为标量；逻辑：取简单类型名后查内置标量类型集合。
    private boolean isScalarResultType(String resultType) {
        String simple = resultType == null ? "" : resultType.trim();
        int generic = simple.indexOf('<');
        if (generic >= 0) {
            simple = simple.substring(0, generic);
        }
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) {
            simple = simple.substring(dot + 1);
        }
        return Set.of(
                "int", "Integer", "long", "Long", "short", "Short", "byte", "Byte",
                "double", "Double", "float", "Float", "boolean", "Boolean", "char",
                "Character", "String", "BigDecimal", "BigInteger", "Date", "LocalDate",
                "LocalDateTime", "Instant", "Timestamp", "byte[]"
        ).contains(simple);
    }

    // 作用：查找关键字首次出现位置；逻辑：使用大小写不敏感的词边界正则从指定位置开始匹配。
    private int keyword(String value, String keyword, int from) {
        Matcher matcher = Pattern.compile("(?i)\\b" + Pattern.quote(keyword) + "\\b")
                .matcher(value);
        return matcher.find(Math.max(0, from)) ? matcher.start() : -1;
    }

    // 作用：查找括号与引号之外的顶层关键字；逻辑：跟踪括号深度和字符串状态，跳过嵌套区域后做词边界比较。
    private int keywordAtTopLevel(String value, String keyword, int from) {
        int depth = 0;
        char quote = 0;
        for (int index = Math.max(0, from); index < value.length() - keyword.length(); index++) {
            char current = value.charAt(index);
            if (quote != 0) {
                if (current == quote && (index == 0 || value.charAt(index - 1) != '\\')) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                continue;
            }
            if (current == '(') {
                depth++;
                continue;
            }
            if (current == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth != 0 || !value.regionMatches(true, index, keyword, 0, keyword.length())) {
                continue;
            }
            boolean before = index == 0 || !Character.isJavaIdentifierPart(value.charAt(index - 1));
            int afterIndex = index + keyword.length();
            boolean after = afterIndex >= value.length()
                    || !Character.isJavaIdentifierPart(value.charAt(afterIndex));
            if (before && after) {
                return index;
            }
        }
        return -1;
    }

    // 作用：拆分 select 字段列表；逻辑：只按顶层逗号切分，忽略括号内与引号内的逗号。
    private List<Range> splitSelectList(String selectList) {
        List<Range> result = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int index = 0; index < selectList.length(); index++) {
            char current = selectList.charAt(index);
            if (quote != 0) {
                if (current == quote && (index == 0 || selectList.charAt(index - 1) != '\\')) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth = Math.max(0, depth - 1);
            } else if (current == ',' && depth == 0) {
                result.add(new Range(start, index));
                start = index + 1;
            }
        }
        result.add(new Range(start, selectList.length()));
        return List.copyOf(result);
    }

    // 作用：从选择表达式提取列别名；逻辑：优先匹配显式 AS 或尾随别名，否则取末尾标识符作为列名。
    private Alias sqlAlias(String expression) {
        Matcher explicit = Pattern.compile(
                "(?is)^(.+?)\\s+(?:as\\s+)?([`\"\\[]?[A-Za-z_$][\\w$]*[`\"\\]]?)$"
        ).matcher(expression);
        if (explicit.matches()) {
            String column = cleanIdentifier(explicit.group(2));
            if (column.isBlank()) {
                return null;
            }
            return new Alias(column, propertyName(column), explicit.start(2));
        }
        Matcher identifier = Pattern.compile(
                "([`\"\\[]?[A-Za-z_$][\\w$]*[`\"\\]]?)$"
        ).matcher(expression);
        if (!identifier.find()) {
            return null;
        }
        String column = cleanIdentifier(identifier.group(1));
        if (column.isBlank() || column.equals("*")) {
            return null;
        }
        return new Alias(column, propertyName(column), identifier.start(1));
    }

    // 作用：去除标识符包裹符；逻辑：循环剥离反引号、双引号或方括号后返回干净名称。
    private String cleanIdentifier(String identifier) {
        String value = identifier == null ? "" : identifier.trim();
        while (value.length() >= 2
                && ((value.startsWith("`") && value.endsWith("`"))
                || (value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("[") && value.endsWith("]")))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    // 作用：把数据库列名转换为属性名；逻辑：按下划线分词，后续单词首字母转大写。
    private String propertyName(String column) {
        if (!column.contains("_")) {
            return column;
        }
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (int index = 0; index < column.length(); index++) {
            char current = column.charAt(index);
            if (current == '_') {
                upper = result.length() > 0;
                continue;
            }
            result.append(upper ? Character.toUpperCase(current) : Character.toLowerCase(current));
            upper = false;
        }
        return result.toString();
    }

    // 作用：去重参数引用；逻辑：以表达式和起止位置为键，保留首次出现的引用。
    private List<MyBatisMapperDefinition.ParameterReference> dedupe(
            List<MyBatisMapperDefinition.ParameterReference> parameters
    ) {
        Map<String, MyBatisMapperDefinition.ParameterReference> unique = new LinkedHashMap<>();
        for (MyBatisMapperDefinition.ParameterReference parameter : parameters) {
            unique.putIfAbsent(
                    parameter.expression() + "|" + parameter.line() + "|" + parameter.columnStart(),
                    parameter
            );
        }
        return List.copyOf(unique.values());
    }

    // 作用：收集 foreach 别名；逻辑：遍历后代节点定位 foreach，记录集合、项、索引与位置，并递归进入 include 片段。
    private void collectForeachAliases(
            Element element,
            String source,
            Range range,
            Map<String, Element> fragments,
            Map<String, Range> fragmentRanges,
            Set<String> visitedFragments,
            List<MyBatisMapperDefinition.ForeachAlias> result
    ) {
        int cursor = range.start();
        for (Element child : descendants(element)) {
            if ("foreach".equals(child.getTagName())) {
                int start = source.indexOf("<foreach", cursor);
                if (start < 0 || start >= range.end()) {
                    continue;
                }
                cursor = start + 1;
                String collection = child.getAttribute("collection");
                String item = child.getAttribute("item");
                if (collection.isBlank() || item.isBlank()) {
                    continue;
                }
                Position position = position(source, start);
                result.add(new MyBatisMapperDefinition.ForeachAlias(
                        collection,
                        item,
                        child.getAttribute("index"),
                        position.line(),
                        position.column()
                ));
                continue;
            }
            if (!"include".equals(child.getTagName())) {
                continue;
            }
            String refid = child.getAttribute("refid");
            if (refid.isBlank() || !visitedFragments.add(refid)) {
                continue;
            }
            Element fragment = fragments.get(refid);
            Range fragmentRange = fragmentRanges.get(refid);
            if (fragment != null && fragmentRange != null) {
                collectForeachAliases(
                        fragment,
                        source,
                        fragmentRange,
                        fragments,
                        fragmentRanges,
                        visitedFragments,
                        result
                );
            }
        }
    }

    // 作用：收集 bind 别名；逻辑：遍历后代节点定位 bind，记录名称、值表达式与位置，并递归进入 include 片段。
    private void collectBindAliases(
            Element element,
            String source,
            Range range,
            Map<String, Element> fragments,
            Map<String, Range> fragmentRanges,
            Set<String> visitedFragments,
            List<MyBatisMapperDefinition.BindAlias> result
    ) {
        int cursor = range.start();
        for (Element child : descendants(element)) {
            if ("bind".equals(child.getTagName())) {
                int start = source.indexOf("<bind", cursor);
                if (start < 0 || start >= range.end()) {
                    continue;
                }
                cursor = start + 1;
                String name = child.getAttribute("name");
                String value = child.getAttribute("value");
                if (name.isBlank() || value.isBlank()) {
                    continue;
                }
                Position position = position(source, start);
                result.add(new MyBatisMapperDefinition.BindAlias(
                        name,
                        value,
                        position.line(),
                        position.column()
                ));
                continue;
            }
            if (!"include".equals(child.getTagName())) {
                continue;
            }
            String refid = child.getAttribute("refid");
            if (refid.isBlank() || !visitedFragments.add(refid)) {
                continue;
            }
            Element fragment = fragments.get(refid);
            Range fragmentRange = fragmentRanges.get(refid);
            if (fragment != null && fragmentRange != null) {
                collectBindAliases(
                        fragment,
                        source,
                        fragmentRange,
                        fragments,
                        fragmentRanges,
                        visitedFragments,
                        result
                );
            }
        }
    }

    // 作用：收集元素的所有后代元素；逻辑：深度优先遍历子节点并递归追加。
    private List<Element> descendants(Element element) {
        List<Element> result = new ArrayList<>();
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element child = (Element) node;
            result.add(child);
            result.addAll(descendants(child));
        }
        return result;
    }

    // 作用：定位元素在原始 XML 中的字符范围；逻辑：从游标查找同名开标签，校验标签边界与 id，再匹配闭标签或自闭合。
    private Range elementRange(String source, Element element, int from) {
        String tag = element.getTagName();
        String id = element.getAttribute("id");
        int cursor = Math.max(0, from);
        while (cursor < source.length()) {
            int start = source.indexOf("<" + tag, cursor);
            if (start < 0) {
                return null;
            }
            int nameEnd = start + 1 + tag.length();
            if (nameEnd >= source.length()
                    || !(Character.isWhitespace(source.charAt(nameEnd))
                    || source.charAt(nameEnd) == '>'
                    || source.charAt(nameEnd) == '/')) {
                cursor = nameEnd;
                continue;
            }
            int tagEnd = source.indexOf('>', start);
            if (tagEnd < 0) {
                return null;
            }
            boolean matchesId = id.isBlank()
                    || source.substring(start, tagEnd + 1).contains("id=\"" + id + "\"");
            if (matchesId) {
                if (tagEnd > start && source.charAt(tagEnd - 1) == '/') {
                    return new Range(start, tagEnd + 1);
                }
                int end = source.indexOf("</" + tag + ">", tagEnd + 1);
                return end < 0
                        ? new Range(start, tagEnd + 1)
                        : new Range(start, end + ("</" + tag + ">").length());
            }
            cursor = tagEnd + 1;
        }
        return null;
    }

    // 作用：把字符偏移转换为行列位置；逻辑：统计偏移前的换行数，并以最后一个换行后的位置计算列号。
    private Position position(String source, int offset) {
        int line = 1;
        int lineStart = 0;
        for (int index = 0; index < offset && index < source.length(); index++) {
            if (source.charAt(index) == '\n') {
                line++;
                lineStart = index + 1;
            }
        }
        return new Position(line, offset - lineStart + 1);
    }

    // 作用：读取 Mapper XML 文本；逻辑：优先 UTF-8，出现替换字符时回退 GB18030，仍失败则保留 UTF-8 结果。
    private String readText(Path path) throws java.io.IOException {
        byte[] bytes = Files.readAllBytes(path);
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) {
            return utf8;
        }
        try {
            return new String(bytes, Charset.forName("GB18030"));
        } catch (RuntimeException ignored) {
            return utf8;
        }
    }

    // 作用：设置解析器安全特性；逻辑：特性不受当前实现支持时忽略，保证兼容不同 JDK。
    private void setFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
        }
    }

    private record Range(int start, int end) {
    }

    private record Position(int line, int column) {
    }

    private record Alias(String column, String property, int offset) {
    }
}
