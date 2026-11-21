package io.fieldtracegraph.javaanalysis.resolution;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;

import java.util.Set;
import java.util.List;
import java.util.Locale;

// 作用：过滤与字段血缘无关的调用。
// 使用场景：跳过日志、toString、equals、hashCode 等噪声。

/**
 * Keeps logging, monitoring and serialization calls out of the value lineage
 * candidate set. These calls are useful application behavior, but they are not
 * field propagation evidence.
 */
public final class NonLineageCallFilter {
    private static final Set<String> LOG_METHODS = Set.of(
            "trace", "debug", "info", "warn", "warning", "error", "fatal",
            "log", "logp", "logrb", "severe", "fine", "finer", "finest",
            "config", "throwing"
    );
    private static final List<String> LOG_METHOD_FRAGMENTS = List.of(
            "error", "warn", "info", "debug", "trace", "fatal"
    );
    private static final Set<String> LOGGER_SIMPLE_TYPES = Set.of(
            "logger", "log", "extendedlogger", "locationawarelogger",
            "category", "jdklogger"
    );
    private static final List<String> LOGGER_TYPE_MARKERS = List.of(
            "org.slf4j",
            "org.apache.log4j",
            "org.apache.logging",
            "org.apache.commons.logging",
            "ch.qos.logback",
            "java.util.logging",
            "org.jboss.logging",
            "io.vertx.core.logging",
            "org.tinylog"
    );
    private static final Set<String> LOGGER_FACTORY_METHODS = Set.of(
            "getlogger", "getlog"
    );
    private static final Set<String> GENERIC_LOGGER_NAMES = Set.of(
            "log", "logger", "_log"
    );
    private static final Set<String> CONSOLE_METHODS = Set.of(
            "print", "println", "printf", "format"
    );

    private static final Set<String> PURE_CONVERSIONS = Set.of(
            "toString",
            "valueOf",
            "trim",
            "strip",
            "toPlainString",
            "toBigInteger",
            "toBigDecimal",
            "stripTrailingZeros",
            "setScale",
            "intValue",
            "longValue",
            "doubleValue",
            "floatValue",
            "booleanValue",
            "stringValue",
            "parseLong",
            "parseInt",
            "parseDouble",
            "parseFloat",
            "parseBoolean",
            "toLong",
            "toInteger",
            "toInt",
            "toDouble",
            "toFloat",
            "requireNonNull",
            "requireNonNullElse",
            "requireNonNullElseGet",
            "checkNotNull",
            "defaultIfNull",
            "defaultIfBlank",
            "defaultIfEmpty",
            "defaultString",
            "firstNonNull",
            "orElse",
            "orElseGet",
            "orElseThrow"
    );
    private static final Set<String> PURE_UTILITY_TYPES = Set.of(
            "StringUtils", "CollectionUtils", "ObjectUtils", "NumberUtils",
            "BooleanUtils", "ArrayUtils", "MapUtils", "Objects"
    );
    private static final Set<String> PURE_PREDICATES = Set.of(
            "equals", "equalsIgnoreCase", "contains", "containsKey",
            "containsValue", "startsWith", "endsWith", "matches", "test",
            "isEmpty", "isNotEmpty", "isBlank", "isNotBlank", "hasText",
            "compare", "compareTo", "contentEquals"
    );

    private static final Set<String> IGNORED_RECEIVERS = Set.of(
            "LOGGER",
            "Profiler",
            "metric",
            "metrics",
            "monitor",
            "telemetry",
            "tracer",
            "alarm",
            "alert",
            "counter",
            "histogram",
            "meter",
            "log",
            "logUtil",
            "LoggerUtil",
            "logger"
    );
    // 作用：识别 Micrometer、OpenTelemetry 等开源监控/追踪 API 的埋点动作；逻辑：方法名白名单，命中后再看接收者是否像监控组件。
    private static final Set<String> TELEMETRY_METHODS = Set.of(
            "trace", "track", "report", "record", "mark", "emit", "increment",
            "decrement", "counter", "count", "gauge", "timer", "histogram",
            "observe", "summary", "startspan", "setattribute"
    );
    private static final Set<String> TELEMETRY_SCOPE_MARKERS = Set.of(
            "metric", "monitor", "telemetry", "tracer", "alarm", "counter",
            "histogram", "meter", "profiler", "micrometer", "opentelemetry",
            "otel", "prometheus", "statsd", "dropwizard", "actuator", "span"
    );

    // 作用：综合判断调用是否为血缘噪声；逻辑：排除诊断、日志、监控、纯转换与控制条件调用，但保留 JSON 序列化。
    public boolean shouldIgnore(MethodCallExpr call, JavaValueFlowContext context) {
        if (isInDiagnosticContext(call, context)) {
            return true;
        }
        if ("toJSONString".equals(call.getNameAsString())) {
            return false;
        }
        if (isLogCall(call, context)) {
            return true;
        }
        if (isLogHelperName(call.getNameAsString())) {
            return true;
        }
        if (isConsoleCall(call)) {
            return true;
        }
        if (isTelemetryCall(call)) {
            return true;
        }
        if (call.getScope()
                .map(Expression::toString)
                .map(this::simpleName)
                .map(IGNORED_RECEIVERS::contains)
                .orElse(false)) {
            return true;
        }
        if (isValueTransform(call, context)) {
            return true;
        }
        return isControlConditionCall(call);
    }

    /**
     * A same-class helper used only to evaluate a control condition still
     * carries business value flow: its arguments decide which branch the
     * method takes. Keeping the call keeps that branch and every field the
     * helper reads reachable from target discovery and cross-method lineage.
     */
    // 作用：判断控制条件中的同类辅助调用是否应保留；逻辑：要求无接收者或 this 接收者，且当前类确实声明同名方法。
    public boolean isRetainedControlCall(
            MethodCallExpr call,
            com.github.javaparser.ast.body.CallableDeclaration<?> callable
    ) {
        if (!isControlConditionCall(call)) {
            return false;
        }
        boolean unqualified = call.getScope().isEmpty()
                || call.getScope().map(Expression::isThisExpr).orElse(false);
        if (!unqualified) {
            return false;
        }
        return callable.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class)
                .map(type -> type.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
                        .anyMatch(method -> method.getNameAsString().equals(call.getNameAsString())))
                .orElse(false);
    }

    // 作用：判断调用是否位于诊断或日志调用内部；逻辑：向上遍历到方法边界，命中诊断类父调用即返回真。
    public boolean isInDiagnosticContext(MethodCallExpr call) {
        return isInDiagnosticContext(call, null);
    }

    // 作用：判断调用是否位于诊断或日志调用内部；逻辑：带类型上下文向上遍历，命中诊断类父调用即返回真。
    public boolean isInDiagnosticContext(MethodCallExpr call, JavaValueFlowContext context) {
        for (Node ancestor = call.getParentNode().orElse(null);
             ancestor != null;
             ancestor = ancestor.getParentNode().orElse(null)) {
            if (ancestor instanceof MethodCallExpr parent
                    && shouldIgnoreDiagnosticCall(parent, context)) {
                return true;
            }
            if (ancestor instanceof com.github.javaparser.ast.body.CallableDeclaration<?>) {
                return false;
            }
        }
        return false;
    }

    // 作用：判断父调用是否属于诊断输出；逻辑：匹配忽略方法名、日志/监控调用或忽略接收者。
    private boolean shouldIgnoreDiagnosticCall(
            MethodCallExpr call,
            JavaValueFlowContext context
    ) {
        if ("toJSONString".equals(call.getNameAsString())) {
            return true;
        }
        if (isLogCall(call, context) || isConsoleCall(call) || isTelemetryCall(call)) {
            return true;
        }
        if (isLogHelperName(call.getNameAsString())) {
            return true;
        }
        return call.getScope()
                .map(Expression::toString)
                .map(this::simpleName)
                .map(IGNORED_RECEIVERS::contains)
                .orElse(false);
    }

    /**
     * Pure conversions preserve the origin of their input. They are removed
     * from the call graph as external boundaries, while the extractor keeps
     * their receiver/arguments as value sources.
     */
    // 作用：判断是否为纯值转换方法；逻辑：方法名出现在内置转换集合中即成立。
    public boolean isPureConversion(MethodCallExpr call) {
        return PURE_CONVERSIONS.contains(call.getNameAsString());
    }

    /**
     * Treat a chained no-argument instance call as a value transformation when
     * its receiver is itself a value-producing expression. This covers
     * conversions such as {@code source.getTime().getTime()} and
     * {@code source.getSkuId().longValue()} without maintaining a method-name
     * allow-list for every return type. A bare type receiver is deliberately
     * excluded so {@code Factory.get(value)} remains a call boundary.
     */
    // 作用：判断链式调用是否保持值来源；逻辑：纯转换直接成立，链式值表达式要求无参，已知值类型接收者也视为变换。
    public boolean isValueTransform(MethodCallExpr call, JavaValueFlowContext context) {
        if (isPureConversion(call)) {
            return true;
        }
        if (isPureUtilityCall(call)) {
            return true;
        }
        if (PURE_PREDICATES.contains(call.getNameAsString())) {
            return true;
        }
        if (call.getScope().isEmpty()) {
            return false;
        }
        Expression receiver = unwrap(call.getScope().orElseThrow());
        if (receiver instanceof MethodCallExpr
                || receiver instanceof ObjectCreationExpr
                || receiver instanceof ArrayAccessExpr) {
            return call.getArguments().isEmpty();
        }
        return isKnownValueTypeReceiver(receiver, context);
    }

    // 作用：判断是否为纯工具类调用；逻辑：接收者是常见无副作用工具类型时，参数即结果来源。
    private boolean isPureUtilityCall(MethodCallExpr call) {
        String receiver = call.getScope()
                .map(Object::toString)
                .map(value -> {
                    int dot = value.lastIndexOf('.');
                    return dot < 0 ? value : value.substring(dot + 1);
                })
                .orElse("");
        return PURE_UTILITY_TYPES.contains(receiver);
    }

    // 作用：判断接收者是否为已知值类型；逻辑：按上下文键、this 字段名与去空白文本依次查声明类型。
    private boolean isKnownValueTypeReceiver(Expression receiver, JavaValueFlowContext context) {
        if (context == null) {
            return false;
        }
        String type = context.declaredType(context.key(receiver));
        if (type == null && receiver instanceof com.github.javaparser.ast.expr.FieldAccessExpr field
                && field.getScope().isThisExpr()) {
            type = context.declaredType(field.getNameAsString());
        }
        if (type == null) {
            type = context.declaredType(receiver.toString().replaceAll("\\s+", ""));
        }
        return JavaValueTypes.isValueType(type);
    }

    /**
     * Calls used only to evaluate an if/loop condition are control
     * dependencies. They do not produce the value written by an assignment or
     * returned by a method, so they must not become value-provenance calls.
     */
    // 作用：判断调用是否只用于控制条件；逻辑：向上查找 if、循环、断言、三元与 switch 的条件区域。
    public boolean isControlConditionCall(MethodCallExpr call) {
        for (Node ancestor = call.getParentNode().orElse(null); ancestor != null; ancestor = ancestor.getParentNode().orElse(null)) {
            if (ancestor instanceof IfStmt statement && within(statement.getCondition(), call)) {
                return true;
            }
            if (ancestor instanceof WhileStmt statement && within(statement.getCondition(), call)) {
                return true;
            }
            if (ancestor instanceof DoStmt statement && within(statement.getCondition(), call)) {
                return true;
            }
            if (ancestor instanceof ForStmt statement
                    && statement.getCompare().filter(compare -> within(compare, call)).isPresent()) {
                return true;
            }
            if (ancestor instanceof AssertStmt statement && within(statement.getCheck(), call)) {
                return true;
            }
            if (ancestor instanceof ConditionalExpr conditional && within(conditional.getCondition(), call)) {
                return true;
            }
            if (ancestor instanceof SwitchStmt statement && within(statement.getSelector(), call)) {
                return true;
            }
            if (ancestor instanceof com.github.javaparser.ast.body.CallableDeclaration<?>) {
                return false;
            }
        }
        return false;
    }

    // 作用：判断节点是否位于指定祖先区域内；逻辑：祖先等于节点或确实是其祖先时成立。
    private boolean within(Node ancestor, Node candidate) {
        return ancestor == candidate || ancestor.isAncestorOf(candidate);
    }

    // 作用：解包表达式括号与强转；逻辑：循环剥离外层结构直到得到原始表达式。
    private Expression unwrap(Expression expression) {
        Expression value = expression;
        while (value instanceof EnclosedExpr enclosed || value instanceof CastExpr) {
            value = value instanceof EnclosedExpr
                    ? ((EnclosedExpr) value).getInner()
                    : ((CastExpr) value).getExpression();
        }
        return value;
    }

    // 作用：提取点号分隔文本的末段名称；逻辑：截取最后一个点号后的内容，无点号时原样返回。
    private String simpleName(String value) {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    // 作用：判断调用是否为监控埋点；逻辑：方法名属于监控动作且接收者文本包含监控组件标记。
    private boolean isTelemetryCall(MethodCallExpr call) {
        String method = call.getNameAsString().toLowerCase(Locale.ROOT);
        if (!TELEMETRY_METHODS.contains(method)) {
            return false;
        }
        String receiver = call.getScope()
                .map(Expression::toString)
                .orElse("")
                .toLowerCase(Locale.ROOT);
        return TELEMETRY_SCOPE_MARKERS.stream().anyMatch(receiver::contains);
    }

    // 作用：判断调用是否为日志输出；逻辑：方法名以日志级别开头，且接收者形态像日志器。
    private boolean isLogCall(MethodCallExpr call, JavaValueFlowContext context) {
        String method = call.getNameAsString().toLowerCase(Locale.ROOT);
        return isLogMethod(method) && looksLikeLogger(call, context);
    }

    // 作用：判断方法名是否表达日志动作；逻辑：标准级别名直接命中，兼容 functionError 这类包装方法。
    private boolean isLogMethod(String method) {
        return LOG_METHODS.contains(method)
                || method.startsWith("log")
                || LOG_METHOD_FRAGMENTS.stream().anyMatch(method::contains);
    }

    /**
     * Custom log helpers such as {@code writeLogAndCheckJbean(...)} keep the
     * logging verb in a camel-case word instead of the method prefix. Their
     * arguments are log payloads, so serialized beans inside them must not be
     * treated as business lineage.
     */
    // 作用：识别名称中包含独立 log 词的包装日志方法；逻辑：按驼峰分词后比较完整单词，避免误伤 catalog/login。
    private boolean isLogHelperName(String method) {
        int start = 0;
        for (int i = 1; i <= method.length(); i++) {
            boolean boundary = i == method.length()
                    || (Character.isUpperCase(method.charAt(i))
                        && !Character.isUpperCase(method.charAt(i - 1)))
                    || !Character.isLetterOrDigit(method.charAt(i));
            if (!boundary) {
                continue;
            }
            String word = method.substring(start, i).toLowerCase(Locale.ROOT);
            if ("log".equals(word) || "logs".equals(word) || "logger".equals(word)) {
                return true;
            }
            while (i < method.length() && !Character.isLetterOrDigit(method.charAt(i))) {
                i++;
            }
            start = i;
        }
        return false;
    }

    /**
     * Logger detection is type/API based rather than tied to one company's
     * wrapper classes. A receiver is a logger when its declared type is a
     * known logging type or package, when it is produced by a logger factory,
     * or when it is one of the conventional generic logger variables.
     */
    // 作用：判断接收者是否像日志器；逻辑：按 Logger 类型、Logger 工厂调用与通用变量名识别。
    private boolean looksLikeLogger(MethodCallExpr call, JavaValueFlowContext context) {
        Expression receiver = call.getScope().orElse(null);
        if (receiver == null) {
            return false;
        }
        String receiverText = receiver.toString().replaceAll("\\s+", "").trim();
        if (receiverText.isBlank()) {
            return false;
        }
        Expression value = unwrap(receiver);
        if (value instanceof MethodCallExpr factory && isLoggerFactoryCall(factory)) {
            return true;
        }
        String declaredType = declaredType(call, receiverText, context);
        if (isLoggerType(declaredType)) {
            return true;
        }
        String simpleReceiver = simpleName(receiverText).toLowerCase(Locale.ROOT);
        return looksLikeLoggerName(simpleReceiver);
    }

    // 作用：判断接收者名称是否表达日志器；逻辑：通用 log/logger 名、含 Logger 的包装类以及 LogUtil/Logging 后缀。
    private boolean looksLikeLoggerName(String name) {
        return GENERIC_LOGGER_NAMES.contains(name)
                || name.contains("logger")
                || name.endsWith("logutil")
                || name.endsWith("loggerutil")
                || name.endsWith("logging")
                || isProjectLogTraceName(name);
    }

    /*
     * In-house log facades are named after the trace channel rather than
     * "Logger": RequestLogTrace.info(...) took a whole object graph as
     * varargs and every argument then looked like a field consumer, which
     * produced `call:size(0).msg` style edges. Matching the suffix keeps the
     * rule narrow: generic business log facades deliberately stay in the graph.
     */
    // 作用：识别项目内日志门面命名；逻辑：按 LogTrace/TraceLog/LogHelper 后缀匹配。
    private boolean isProjectLogTraceName(String name) {
        return name.endsWith("logtrace")
                || name.endsWith("tracelog")
                || name.endsWith("loghelper");
    }

    // 作用：判断调用是否输出到控制台；逻辑：接收者为 System.out/System.err，方法名为 print 系列。
    private boolean isConsoleCall(MethodCallExpr call) {
        String receiver = call.getScope()
                .map(Expression::toString)
                .map(value -> value.replaceAll("\\s+", ""))
                .orElse("");
        return ("System.out".equals(receiver) || "System.err".equals(receiver))
                && CONSOLE_METHODS.contains(call.getNameAsString());
    }

    // 作用：判断工厂调用是否返回日志器；逻辑：方法名为 getLogger/getLog，或接收者是 LoggerFactory/LogManager。
    private boolean isLoggerFactoryCall(MethodCallExpr call) {
        String method = call.getNameAsString().toLowerCase(Locale.ROOT);
        if (LOGGER_FACTORY_METHODS.contains(method)) {
            return true;
        }
        String receiver = call.getScope()
                .map(Expression::toString)
                .map(this::simpleName)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .orElse("");
        return receiver.endsWith("loggerfactory")
                || receiver.endsWith("logfactory")
                || receiver.endsWith("logmanager");
    }

    // 作用：判断声明类型是否为日志器；逻辑：简单类型命中 Logger/Log/Category，或全名包含常见日志框架包。
    private boolean isLoggerType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String simpleName = simpleType(type).toLowerCase(Locale.ROOT);
        if (LOGGER_SIMPLE_TYPES.contains(simpleName)
                || simpleName.contains("logger")
                || simpleName.endsWith("logutil")
                || simpleName.endsWith("logging")
                || isProjectLogTraceName(simpleName)) {
            return true;
        }
        String fullName = type.toLowerCase(Locale.ROOT);
        return LOGGER_TYPE_MARKERS.stream().anyMatch(fullName::contains);
    }

    // 作用：查找接收者文本对应的声明类型；逻辑：依次检查方法参数、局部变量与所属类型字段，未命中返回空串。
    private String declaredType(
            MethodCallExpr call,
            String receiver,
            JavaValueFlowContext context
    ) {
        String normalized = receiver;
        if (normalized.startsWith("this.")) {
            normalized = normalized.substring("this.".length());
        } else if (normalized.startsWith("super.")) {
            normalized = normalized.substring("super.".length());
        }
        String simple = simpleName(normalized);
        if (context != null) {
            String type = context.declaredType(normalized);
            if (type == null) {
                type = context.declaredType(simple);
            }
            if (type != null) {
                return type;
            }
        }
        CallableDeclaration<?> callable = call.findAncestor(CallableDeclaration.class).orElse(null);
        if (callable == null) {
            return "";
        }
        for (Parameter parameter : callable.getParameters()) {
            if (parameter.getNameAsString().equals(normalized)
                    || parameter.getNameAsString().equals(simple)) {
                return parameter.getType().asString();
            }
        }
        for (VariableDeclarator variable : callable.findAll(VariableDeclarator.class)) {
            if (variable.getNameAsString().equals(normalized)
                    || variable.getNameAsString().equals(simple)) {
                return variable.getType().asString();
            }
        }
        com.github.javaparser.ast.body.TypeDeclaration<?> type =
                callable.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class).orElse(null);
        if (type == null) {
            return "";
        }
        for (FieldDeclaration field : type.getFields()) {
            for (VariableDeclarator variable : field.getVariables()) {
                if (variable.getNameAsString().equals(normalized)
                        || variable.getNameAsString().equals(simple)) {
                    return variable.getType().asString();
                }
            }
        }
        return "";
    }

    // 作用：提取声明类型的简单名；逻辑：去掉泛型参数与包名前缀后返回末段名称。
    private String simpleType(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String value = type.trim();
        int generic = value.indexOf('<');
        if (generic >= 0) {
            value = value.substring(0, generic);
        }
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }
}
