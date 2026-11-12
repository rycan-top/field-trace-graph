package io.fieldtracegraph.cli.contract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 作用：读取项目内的 contracts.yml。
// 使用场景：声明人工契约、生成源码根与外部边界。

/**
 * Loads the deliberately small YAML subset used by
 * {@code .field-trace-graph/contracts.yml}. The loader is strict about the
 * schema version and ignores unrelated sections so future FTG metadata can be
 * added without making old binaries fail.
 */
public final class ProjectContractLoader {
    public static final Path DEFAULT_PATH = Path.of(".field-trace-graph", "contracts.yml");
    private static final Pattern DATE = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})");

    // 作用：以非严格模式加载契约；逻辑：复用严格加载入口并关闭校验失败即报错。
    public ProjectContracts load(Path projectRoot) {
        return load(projectRoot, false);
    }

    /**
     * @param strict when true, expired or incomplete contracts fail the
     *               command instead of being skipped with a warning
     */
    // 作用：加载契约文件；逻辑：解析版本、生成源与规则段，校验完整性并过滤过期规则。
    public ProjectContracts load(Path projectRoot, boolean strict) {
        Path file = projectRoot.resolve(DEFAULT_PATH);
        if (!Files.isRegularFile(file)) {
            return ProjectContracts.empty();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + DEFAULT_PATH, e);
        }
        int version = 1;
        List<String> generatedSources = new ArrayList<>();
        List<ProjectContracts.ContractRule> contracts = new ArrayList<>();
        String section = "";
        Map<String, String> current = null;
        for (String raw : lines) {
            String line = stripComment(raw);
            if (line.isBlank()) {
                continue;
            }
            String trimmed = line.trim();
            int indent = line.indexOf(trimmed);
            if (indent == 0) {
                current = flushContract(current, contracts);
                if (trimmed.startsWith("version:")) {
                    version = integer(value(trimmed), 1);
                    section = "";
                } else if ("generatedSources:".equals(trimmed)) {
                    section = "generatedSources";
                } else if ("contracts:".equals(trimmed)) {
                    section = "contracts";
                } else {
                    section = "";
                }
                continue;
            }
            if ("generatedSources".equals(section) && trimmed.startsWith("- ")) {
                generatedSources.add(unquote(trimmed.substring(2).trim()));
                continue;
            }
            if ("contracts".equals(section)) {
                if (trimmed.startsWith("- ")) {
                    current = flushContract(current, contracts);
                    current = new LinkedHashMap<>();
                    put(current, trimmed.substring(2).trim());
                } else if (current != null) {
                    put(current, trimmed);
                }
            }
        }
        flushContract(current, contracts);
        if (version != 1) {
            throw new IllegalArgumentException(
                    "Unsupported contracts.yml version " + version + "; expected 1"
            );
        }
        return new ProjectContracts(version, generatedSources, activeContracts(contracts, strict));
    }

    // 作用：筛选有效契约；逻辑：缺字段或已过期的规则按严格模式报错，否则跳过或告警。
    private List<ProjectContracts.ContractRule> activeContracts(
            List<ProjectContracts.ContractRule> contracts,
            boolean strict
    ) {
        if (contracts.isEmpty()) {
            return List.of();
        }
        List<ProjectContracts.ContractRule> active = new ArrayList<>();
        for (ProjectContracts.ContractRule rule : contracts) {
            if (!complete(rule)) {
                String message = "contracts.yml entry needs from/to, owner, reason and expiresWhen: "
                        + rule.from() + " -> " + rule.to();
                if (strict) {
                    throw new IllegalArgumentException("CONTRACT_INVALID: " + message);
                }
                continue;
            }
            LocalDate expires = expiry(rule.expiresWhen());
            if (expires != null && !expires.isAfter(LocalDate.now())) {
                String message = "contracts.yml entry expired on " + expires + ": "
                        + rule.from() + " -> " + rule.to();
                if (strict) {
                    throw new IllegalArgumentException("CONTRACT_EXPIRED: " + message);
                }
                io.fieldtracegraph.cli.log.CliLog.warn(message);
                continue;
            }
            active.add(rule);
        }
        return List.copyOf(active);
    }

    // 作用：判断契约字段是否完整；逻辑：from、to、owner、reason 与失效条件均非空才通过。
    private boolean complete(ProjectContracts.ContractRule rule) {
        return rule.from() != null && !rule.from().isBlank()
                && rule.to() != null && !rule.to().isBlank()
                && rule.owner() != null && !rule.owner().isBlank()
                && rule.reason() != null && !rule.reason().isBlank()
                && rule.expiresWhen() != null && !rule.expiresWhen().isBlank();
    }

    /**
     * Contracts may carry a free-form invalidation condition. Only an explicit
     * calendar date is machine-checkable; other text stays human-owned.
     */
    // 作用：解析失效日期；逻辑：从文本中提取年月日，无法解析时返回 null 交由人工判断。
    private LocalDate expiry(String value) {
        Matcher matcher = DATE.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            );
        } catch (DateTimeException | NumberFormatException ignored) {
            return null;
        }
    }

    // 作用：提交当前规则；逻辑：把累积的键值转换为契约记录并清空当前上下文。
    private Map<String, String> flushContract(
            Map<String, String> current,
            List<ProjectContracts.ContractRule> contracts
    ) {
        if (current == null) {
            return null;
        }
        contracts.add(new ProjectContracts.ContractRule(
                current.get("from"),
                current.get("to"),
                current.get("reason"),
                current.get("owner"),
                current.get("expiresWhen"),
                current.getOrDefault("confidence", "EXACT_STATIC")
        ));
        return null;
    }

    // 作用：写入一个键值对；逻辑：按首个冒号切分键与值并去除引号。
    private void put(Map<String, String> values, String line) {
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return;
        }
        values.put(
                line.substring(0, colon).trim(),
                unquote(line.substring(colon + 1).trim())
        );
    }

    // 作用：提取冒号后的值；逻辑：无冒号时返回空串，否则去引号返回。
    private String value(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? "" : unquote(line.substring(colon + 1).trim());
    }

    // 作用：解析整数；逻辑：解析失败时返回兜底值。
    private int integer(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    // 作用：去掉行内注释；逻辑：跟踪单双引号状态，只在引号外截断井号。
    private String stripComment(String line) {
        boolean single = false;
        boolean doubleQuoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !doubleQuoted) {
                single = !single;
            } else if (c == '"' && !single) {
                doubleQuoted = !doubleQuoted;
            } else if (c == '#' && !single && !doubleQuoted) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    // 作用：去除成对引号；逻辑：首尾同为单引号或双引号时截去两端。
    private String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
