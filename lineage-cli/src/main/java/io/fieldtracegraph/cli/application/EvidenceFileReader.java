package io.fieldtracegraph.cli.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fieldtracegraph.cli.dto.EvidenceImportItem;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 作用：读取并解析运行时证据文件。
// 使用场景：导入规范 JSON、OTLP JSON 或 JFR JSON 前做格式适配。

/**
 * Reads the normalized runtime-evidence JSON shared by import and diff.
 */
public final class EvidenceFileReader {
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 作用：读取证据文件；逻辑：优先识别标准证据数组，否则尝试 OTLP/JFR 适配格式。
    public List<EvidenceImportItem> read(Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("EVIDENCE_FILE_NOT_FOUND");
        }
        JsonNode root = objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
        if (!root.path("evidence").isArray()) {
            List<EvidenceImportItem> adapted = readAdapterFormat(root);
            if (!adapted.isEmpty()) {
                return adapted;
            }
        }
        if (root.path("schemaVersion").asInt(1) != 1) {
            throw new IllegalArgumentException("unsupported evidence schemaVersion");
        }
        JsonNode entries = root.path("evidence");
        if (!entries.isArray()) {
            throw new IllegalArgumentException("evidence must be an array");
        }
        List<EvidenceImportItem> result = new ArrayList<>();
        for (JsonNode entry : entries) {
            JsonNode details = entry.path("details");
            result.add(new EvidenceImportItem(
                    text(entry, "evidenceId"),
                    text(entry, "logicalEdgeKey"),
                    text(entry, "scopeSymbolId"),
                    text(entry, "sourceEdgeId"),
                    text(entry, "evidenceKind"),
                    text(entry, "adapter"),
                    text(entry, "rule"),
                    text(entry, "coverage"),
                    text(entry, "observedAt"),
                    details.isMissingNode() || details.isNull()
                            ? "{}"
                            : objectMapper.writeValueAsString(details)
            ));
        }
        return List.copyOf(result);
    }

    /**
     * Reads OpenTelemetry OTLP JSON, JFR JSON exports and other runtime
     * captures that carry FTG attributes. The required attribute contract is
     * {@code ftg.logicalEdgeKey} plus {@code ftg.coverage}; everything else is
     * optional. Attributes may appear directly on an object, inside an OTLP
     * {@code attributes} array, or inside a JFR {@code values} object.
     */
    // 作用：解析适配格式；逻辑：递归收集带逻辑边属性的记录并逐条转换，缺少覆盖范围时报错。
    private List<EvidenceImportItem> readAdapterFormat(JsonNode root) throws Exception {
        List<Map<String, String>> records = new ArrayList<>();
        collectRecords(root, records, 0);
        List<EvidenceImportItem> result = new ArrayList<>();
        for (Map<String, String> attributes : records) {
            String logicalEdgeKey = first(attributes,
                    "ftg.logicalEdgeKey", "ftg.logical_edge_key");
            if (logicalEdgeKey == null || logicalEdgeKey.isBlank()) {
                continue;
            }
            String coverage = first(attributes, "ftg.coverage", "ftg.coverageScope");
            if (coverage == null || coverage.isBlank()) {
                throw new IllegalArgumentException(
                        "EVIDENCE_COVERAGE_REQUIRED: ftg.coverage is required for "
                                + logicalEdgeKey);
            }
            result.add(new EvidenceImportItem(
                    first(attributes, "ftg.evidenceId", "ftg.evidence_id"),
                    logicalEdgeKey,
                    first(attributes, "ftg.scopeSymbolId", "ftg.scope_symbol_id"),
                    first(attributes, "ftg.sourceEdgeId", "ftg.source_edge_id"),
                    first(attributes, "ftg.evidenceKind", "ftg.evidence_kind"),
                    first(attributes, "ftg.adapter"),
                    first(attributes, "ftg.rule"),
                    coverage,
                    observedAt(attributes),
                    objectMapper.writeValueAsString(attributes)
            ));
        }
        return List.copyOf(result);
    }

    // 作用：递归收集候选记录；逻辑：深度受限遍历对象与数组，命中逻辑边属性时纳入结果。
    private void collectRecords(JsonNode node, List<Map<String, String>> records, int depth) {
        if (node == null || depth > 32) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectRecords(item, records, depth + 1);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        Map<String, String> attributes = attributes(node);
        if (attributes.containsKey("ftg.logicalEdgeKey")
                || attributes.containsKey("ftg.logical_edge_key")) {
            records.add(attributes);
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if ("values".equals(entry.getKey()) || "attributes".equals(entry.getKey())) {
                continue;
            }
            JsonNode value = entry.getValue();
            if (value.isArray() || value.isObject()) {
                collectRecords(value, records, depth + 1);
            }
        }
    }

    // 作用：汇总节点属性；逻辑：合并 OTLP 属性数组、JFR 值对象、直接 ftg 字段与时间戳。
    private Map<String, String> attributes(JsonNode node) {
        Map<String, String> attributes = new LinkedHashMap<>();
        JsonNode direct = node.path("attributes");
        if (direct.isArray()) {
            for (JsonNode attribute : direct) {
                String key = text(attribute, "key");
                if (key == null) {
                    continue;
                }
                attributes.put(key, otlpValue(attribute.path("value")));
            }
        } else if (direct.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = direct.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                attributes.put(entry.getKey(), scalar(entry.getValue()));
            }
        }
        JsonNode values = node.path("values");
        if (values.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = values.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                attributes.putIfAbsent(entry.getKey(), scalar(entry.getValue()));
            }
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getKey().startsWith("ftg.") && entry.getValue().isValueNode()) {
                attributes.putIfAbsent(entry.getKey(), entry.getValue().asText());
            }
        }
        for (String field : List.of("startTimeUnixNano", "timeUnixNano",
                "timestamp", "observedAt", "startTime")) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode()) {
                attributes.putIfAbsent(field, value.asText());
            }
        }
        return attributes;
    }

    // 作用：解析 OTLP 属性值；逻辑：按字符串、布尔、整数、浮点与字节顺序取首个标量。
    private String otlpValue(JsonNode value) {
        for (String field : List.of("stringValue", "boolValue", "intValue",
                "doubleValue", "bytesValue")) {
            JsonNode candidate = value.get(field);
            if (candidate != null && candidate.isValueNode()) {
                return candidate.asText();
            }
        }
        return scalar(value);
    }

    // 作用：读取标量文本；逻辑：空值或容器节点返回 null，其余返回文本。
    private String scalar(JsonNode value) {
        return value == null || value.isNull() || value.isContainerNode()
                ? null
                : value.asText();
    }

    // 作用：解析观测时间；逻辑：优先显式时间字段，否则把纳秒时间戳换算为 ISO 时间。
    private String observedAt(Map<String, String> attributes) {
        String explicit = first(attributes,
                "ftg.observedAt", "observedAt", "timestamp", "startTime");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String nanos = first(attributes, "startTimeUnixNano", "timeUnixNano");
        if (nanos != null && !nanos.isBlank()) {
            try {
                long value = Long.parseLong(nanos);
                return Instant.ofEpochSecond(value / 1_000_000_000L, value % 1_000_000_000L)
                        .toString();
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    // 作用：取首个非空属性；逻辑：按候选键顺序返回第一个非空白值。
    private String first(Map<String, String> attributes, String... keys) {
        for (String key : keys) {
            String value = attributes.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    // 作用：读取节点文本字段；逻辑：字段缺失或为 null 时返回 null，否则返回文本。
    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
