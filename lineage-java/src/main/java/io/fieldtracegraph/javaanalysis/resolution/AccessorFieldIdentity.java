package io.fieldtracegraph.javaanalysis.resolution;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.body.CallableDeclaration;
import io.fieldtracegraph.javaanalysis.JavaExpressionIdentity;

// 作用：标识访问器与字段的对应关系。
// 使用场景：把 getter/setter 归一到同一个字段身份。

/**
 * Normalizes JavaBean and Lombok-style accessor calls into field paths.
 * Method resolution remains in {@link MethodCallResolver}; this class only
 * owns the accessor naming rule.
 */
public final class AccessorFieldIdentity {
    private static final String FIELD_WRITE_PREFIX = "field-write:";
    private static final String OBJECT_CLASS_ACCESSOR = "getClass";

    // 作用：阻止实例化工具类；逻辑：构造器保持私有。
    private AccessorFieldIdentity() {
    }

    // 作用：推导 getter 读取的字段路径；逻辑：排除运行时内省链与带参调用，按 get/is 规则取属性并拼接接收者。
    public static String readFieldPath(MethodCallExpr call) {
        if (belongsToObjectIntrospectionChain(call)) {
            return null;
        }
        String property = propertyFromAccessor(call.getNameAsString(), true);
        if (property == null || !call.getArguments().isEmpty()) {
            return null;
        }
        return qualify(call, property);
    }

    // 作用：推导 setter 写入的字段路径；逻辑：要求单参数且接收者不是值类型，再按 set 规则取属性并拼接接收者。
    public static String writeFieldPath(MethodCallExpr call) {
        if (belongsToObjectIntrospectionChain(call)) {
            return null;
        }
        String property = propertyFromAccessor(call.getNameAsString(), false);
        if (property == null || call.getArguments().size() != 1
                || JavaValueTypes.isValueReceiver(call.getScope().orElse(null),
                call.findAncestor(CallableDeclaration.class).orElse(null))) {
            return null;
        }
        return qualify(call, property);
    }

    // 作用：生成字段写入键；逻辑：能解析写入路径时加上固定前缀，否则返回空。
    public static String writeKey(MethodCallExpr call) {
        String path = writeFieldPath(call);
        return path == null ? null : FIELD_WRITE_PREFIX + path;
    }

    // 作用：判断调用是否为 getter；逻辑：读取字段路径可解析即为 getter。
    public static boolean isGetter(MethodCallExpr call) {
        return readFieldPath(call) != null;
    }

    // 作用：判断调用是否为 setter；逻辑：写入字段路径可解析即为 setter。
    public static boolean isSetter(MethodCallExpr call) {
        return writeFieldPath(call) != null;
    }

    // 作用：从访问器方法名提取属性名；逻辑：匹配 get/is 或 set 前缀并去掉首字母大写，最后统一布尔字段命名。
    public static String propertyFromAccessor(String methodName, boolean getter) {
        if (methodName == null || getter && OBJECT_CLASS_ACCESSOR.equals(methodName)) {
            return null;
        }
        String[] prefixes = getter ? new String[]{"get", "is"} : new String[]{"set"};
        for (String prefix : prefixes) {
            if (methodName.length() <= prefix.length() || !methodName.startsWith(prefix)) {
                continue;
            }
            char first = methodName.charAt(prefix.length());
            if (!Character.isUpperCase(first)) {
                continue;
            }
            String rest = methodName.substring(prefix.length());
            return canonicalPropertyName(Character.toLowerCase(rest.charAt(0)) + rest.substring(1));
        }
        return null;
    }

    /**
     * {@code Object#getClass()} and its fluent result are runtime reflection
     * values, not JavaBean fields. Reject the whole receiver chain so methods
     * such as {@code getClass().getName()} do not create {@code *.class.name}
     * pseudo fields.
     */
    // 作用：判断调用是否属于 Object 内省链；逻辑：检查当前调用或接收者链中是否出现无参 getClass。
    public static boolean belongsToObjectIntrospectionChain(MethodCallExpr call) {
        if (call == null) {
            return false;
        }
        if (OBJECT_CLASS_ACCESSOR.equals(call.getNameAsString())
                && call.getArguments().isEmpty()) {
            return true;
        }
        Expression scope = call.getScope().orElse(null);
        while (scope instanceof MethodCallExpr receiver) {
            if (OBJECT_CLASS_ACCESSOR.equals(receiver.getNameAsString())
                    && receiver.getArguments().isEmpty()) {
                return true;
            }
            scope = receiver.getScope().orElse(null);
        }
        return false;
    }

    /**
     * Boolean JavaBean fields commonly appear as {@code isGlobal} or
     * {@code IsGlobal}. Business identities use the underlying property name,
     * so both forms collapse to {@code global}; ordinary field names are
     * returned unchanged.
     */
    // 作用：规范化布尔字段名；逻辑：把 is 加大写字母开头的名称折叠为底层属性名，其他名称保持不变。
    public static String canonicalPropertyName(String name) {
        if (name == null || name.length() <= 2
                || !name.regionMatches(true, 0, "is", 0, 2)
                || !Character.isUpperCase(name.charAt(2))) {
            return name;
        }
        String property = name.substring(2);
        return Character.toLowerCase(property.charAt(0)) + property.substring(1);
    }

    // 作用：把属性名限定到接收者；逻辑：无接收者直接返回属性，this 记作 this，其他接收者取表达式键后拼接。
    private static String qualify(MethodCallExpr call, String property) {
        Expression receiver = call.getScope().orElse(null);
        if (receiver == null) {
            return property;
        }
        String receiverPath = receiver instanceof ThisExpr
                ? "this"
                : JavaExpressionIdentity.key(receiver);
        if (receiverPath == null || receiverPath.isBlank()) {
            return property;
        }
        return receiverPath + "." + property;
    }
}
