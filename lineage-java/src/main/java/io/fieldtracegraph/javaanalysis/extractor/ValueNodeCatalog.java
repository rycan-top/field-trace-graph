package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.Node;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;

import java.util.List;
import java.util.Map;

// 作用：维护值节点注册表。
// 使用场景：按表达式或字段查找已有节点，避免重复建点。

/**
 * Creates logical value nodes that do not map one-to-one to a JavaParser
 * expression, such as {@code list[*]}, a map key or a PHI merge result. The
 * catalog only owns identity and location construction; extractors own the
 * rules that decide when a node is needed.
 */
public final class ValueNodeCatalog {
    // 作用：确保值节点存在；逻辑：先按键查找，再按规范值去重，缺失时创建节点并登记。
    public ValueNodeRecord ensure(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            String key,
            String kind,
            Node anchor
    ) {
        ValueNodeRecord existing = nodes.get(key);
        if (existing != null) {
            return existing;
        }
        String canonical = canonical(key);
        boolean logical = isLogicalKey(key);
        for (ValueNodeRecord candidate : nodes.values()) {
            if (canonical.equals(canonical(candidate.canonicalValue()))) {
                nodes.put(key, candidate);
                return candidate;
            }
        }
        int line = anchor.getBegin().map(position -> position.line).orElse(1);
        int column = anchor.getBegin().map(position -> position.column).orElse(1);
        FieldIdentity field = fieldIdentity(canonical);
        ValueNodeRecord created = new ValueNodeRecord(
                Ids.sha256(scope.symbolId(), key, "logical"),
                scope.symbolId(),
                source.fileId(),
                kind == null || kind.isBlank() ? inferKind(canonical) : kind,
                canonical,
                field.rootName(),
                field.accessPath(),
                line * 10_000 + column,
                0,
                null,
                line,
                column,
                anchor.getEnd().map(position -> position.line).orElse(line),
                anchor.getEnd().map(position -> position.column).orElse(column),
                source.relativePath(),
                line,
                column,
                anchor.getEnd().map(position -> position.line).orElse(line),
                anchor.getEnd().map(position -> position.column).orElse(column)
        );
        nodes.put(key, created);
        accumulator.addValueNode(created);
        return created;
    }

    // 作用：规范化节点键；逻辑：字段写入键去掉前缀，其余键原样返回。
    public String canonical(String key) {
        if (key == null) {
            return "";
        }
        int separator = key.indexOf(':');
        return separator >= 0 && key.startsWith("field-write:")
                ? key.substring(separator + 1)
                : key;
    }

    // 作用：判断是否为逻辑键；逻辑：识别 PHI、分支、前态、调用与方法引用等前缀。
    private boolean isLogicalKey(String key) {
        return key != null
                && (key.startsWith("phi:")
                || key.startsWith("branch:")
                || key.startsWith("before:")
                || key.startsWith("call:")
                || key.startsWith("method-ref:"));
    }

    // 作用：拆解字段身份；逻辑：按首个点号拆出根名与访问路径。
    private FieldIdentity fieldIdentity(String canonical) {
        String fieldPath = fieldPath(canonical);
        int dot = fieldPath.indexOf('.');
        if (dot < 0) {
            return new FieldIdentity(fieldPath, List.of());
        }
        return new FieldIdentity(
                fieldPath.substring(0, dot),
                List.of(fieldPath.substring(dot + 1).split("\\."))
        );
    }

    // 作用：提取字段路径；逻辑：剥离逻辑前缀与版本后缀后返回路径文本。
    private String fieldPath(String canonical) {
        String value = canonical;
        if (value.startsWith("branch:")) {
            value = value.substring("branch:".length());
        } else if (value.startsWith("phi:")) {
            value = value.substring("phi:".length());
        } else if (value.startsWith("before:")) {
            value = value.substring("before:".length());
        }
        if (value.startsWith("field-write:")) {
            value = value.substring("field-write:".length());
        }
        int versionSeparator = value.indexOf('@');
        return versionSeparator < 0 ? value : value.substring(0, versionSeparator);
    }

    private record FieldIdentity(String rootName, List<String> accessPath) {
    }

    // 作用：推断节点种类；逻辑：按前缀识别 PHI、表达式、常量、调用结果、容器元素与字段。
    private String inferKind(String canonical) {
        if (canonical.startsWith("phi:")) {
            return "PHI";
        }
        if (canonical.startsWith("branch:")) {
            return "EXPRESSION";
        }
        if (canonical.startsWith("before:")) {
            return "EXPRESSION";
        }
        if (canonical.startsWith("method-ref:")) {
            return "EXPRESSION";
        }
        if (canonical.startsWith("reference:")) {
            return "EXPRESSION";
        }
        if (canonical.startsWith("literal:")) {
            return "CONSTANT";
        }
        if (canonical.startsWith("call:") || canonical.startsWith("new:")) {
            return "CALL_RESULT";
        }
        if (canonical.contains("[*]") || canonical.matches(".*\\[[^]]+].*")) {
            return "CONTAINER_ELEMENT";
        }
        if (canonical.contains(".")) {
            return "FIELD";
        }
        return "EXPRESSION";
    }
}
