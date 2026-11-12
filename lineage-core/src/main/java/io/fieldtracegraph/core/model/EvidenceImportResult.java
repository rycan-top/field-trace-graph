package io.fieldtracegraph.core.model;

// 作用：运行时证据导入结果。
// 使用场景：evidence import 命令汇报导入了多少条证据、跳过多少条。

public record EvidenceImportResult(int importedEvidenceCount) {
}
