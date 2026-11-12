package io.fieldtracegraph.cli.contract;

import java.util.List;

// 作用：承载解析后的契约集合。
// 使用场景：索引与查询阶段补充人工证据与边界声明。

/**
 * Explicit, project-owned FTG contracts. This is intentionally separate from
 * application configuration such as {@code application.yml}.
 */
public record ProjectContracts(
        int version,
        List<String> generatedSources,
        List<ContractRule> contracts
) {
    // 作用：规范化契约集合；逻辑：空列表替换为空集合并复制为不可变列表。
    public ProjectContracts {
        generatedSources = generatedSources == null ? List.of() : List.copyOf(generatedSources);
        contracts = contracts == null ? List.of() : List.copyOf(contracts);
    }

    // 作用：构造空契约；逻辑：返回版本 1 且无生成源、无规则的对象。
    public static ProjectContracts empty() {
        return new ProjectContracts(1, List.of(), List.of());
    }

    public record ContractRule(
            String from,
            String to,
            String reason,
            String owner,
            String expiresWhen,
            String confidence
    ) {
    }
}
