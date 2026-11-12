package io.fieldtracegraph.javaanalysis.project;

import java.nio.file.Path;
import java.util.List;

// 作用：记录校验后的 classpath 与指纹。
// 使用场景：传给类型索引与语义分析，保证同一次分析使用同一组依赖。

public record ValidatedClasspath(List<Path> paths, String fingerprint) {
}
