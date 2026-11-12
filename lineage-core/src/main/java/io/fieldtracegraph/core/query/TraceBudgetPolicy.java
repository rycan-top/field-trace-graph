package io.fieldtracegraph.core.query;

// 作用：把默认预算值翻译成具体的深度与边数策略。
// 使用场景：未显式指定 --max-depth/--max-edges 时决定遍历上限。

/**
 * Chooses a bounded traversal budget from the target shape.
 *
 * <p>A field trace normally follows a narrow value cone and should not spend
 * the object-level budget on unrelated fields. An object trace intentionally
 * expands all evidence-backed fields and therefore needs a larger budget.</p>
 */
public final class TraceBudgetPolicy {
    public static final int FIELD_MAX_DEPTH = 30;
    public static final int FIELD_MAX_EDGES = 2_500;
    public static final int OBJECT_MAX_DEPTH = TraversalBudget.DEFAULT_MAX_DEPTH;
    public static final int OBJECT_MAX_EDGES = TraversalBudget.DEFAULT_MAX_EDGES;

    // 作用：工具类私有构造；逻辑：禁止实例化。
    private TraceBudgetPolicy() {
    }

    // 作用：把请求的预算解析为实际预算；逻辑：-1 使用字段/对象策略，0 表示不限制，其余取正值。
    public static TraversalBudget resolve(
            String targetExpression,
            int requestedMaxDepth,
            int requestedMaxEdges,
            long timeoutMillis
    ) {
        boolean fieldTarget = isFieldTarget(targetExpression);
        int depth = resolveLimit(
                requestedMaxDepth,
                fieldTarget ? FIELD_MAX_DEPTH : OBJECT_MAX_DEPTH,
                "max-depth"
        );
        int edges = resolveLimit(
                requestedMaxEdges,
                fieldTarget ? FIELD_MAX_EDGES : OBJECT_MAX_EDGES,
                "max-edges"
        );
        return new TraversalBudget(depth, edges, timeoutMillis);
    }

    // 作用：校验并回退单个预算值；逻辑：负数使用默认值，非法或超限值直接报错。
    private static int resolveLimit(int requested, int fallback, String name) {
        if (requested == -1) {
            return fallback;
        }
        if (requested < 1) {
            throw new IllegalArgumentException(name + " must be -1 or a positive integer");
        }
        return requested;
    }

    /**
     * Field targets use a value path (dot or collection index). A plain
     * variable, RETURN, or call expression is an object/result target.
     */
    // 作用：判断目标表达式是否是字段路径；逻辑：含点号且不是方法调用括号形式时视为字段目标。
    public static boolean isFieldTarget(String targetExpression) {
        if (targetExpression == null || targetExpression.isBlank()) {
            return false;
        }
        String value = targetExpression.trim();
        if (value.startsWith("call:") || value.startsWith("new:")) {
            int open = value.indexOf('(');
            if (open < 0) {
                return false;
            }
            int close = matchingParen(value, open);
            if (close < 0) {
                return false;
            }
            return hasPathSuffix(value.substring(close + 1));
        }
        return hasPathSuffix(value);
    }

    // 作用：判断字符串是否带路径后缀；逻辑：检查点号或下标访问形式。
    private static boolean hasPathSuffix(String value) {
        return value.indexOf('.') >= 0 || value.indexOf('[') >= 0;
    }

    // 作用：找到与左括号匹配的右括号；逻辑：按嵌套深度扫描字符串并返回匹配位置。
    private static int matchingParen(String value, int open) {
        int depth = 0;
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean escaped = false;
        for (int index = open; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                continue;
            }
            if (current == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (singleQuoted || doubleQuoted) {
                continue;
            }
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }
}
