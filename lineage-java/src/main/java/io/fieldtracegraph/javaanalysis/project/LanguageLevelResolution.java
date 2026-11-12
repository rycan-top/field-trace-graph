package io.fieldtracegraph.javaanalysis.project;

// 作用：记录语言级别的推断结果。
// 使用场景：doctor 与诊断输出中说明级别来源以及是否为假设。

public record LanguageLevelResolution(String level, boolean assumed, String reason) {
}
