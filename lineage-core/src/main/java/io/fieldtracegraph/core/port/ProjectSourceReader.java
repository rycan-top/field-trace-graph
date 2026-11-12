package io.fieldtracegraph.core.port;

import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.SourceScanOptions;

import java.nio.file.Path;
import java.util.List;

// 作用：项目源文件采集端口。
// 使用场景：索引前后各采集一次，用于快照一致性校验与增量判断。

/**
 * Reads the analysed project's Java sources under the configured path and
 * budget policy. Implementations own path safety and exclusion rules.
 */
public interface ProjectSourceReader {
    // 作用：采集项目源文件快照；逻辑：按扫描选项遍历 Java 文件并生成路径、哈希与内容记录。
    List<SourceFileRecord> capture(Path projectRoot, SourceScanOptions options);
}
