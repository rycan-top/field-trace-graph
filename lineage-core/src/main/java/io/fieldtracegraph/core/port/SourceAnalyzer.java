package io.fieldtracegraph.core.port;

import io.fieldtracegraph.core.model.AnalysisResult;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.SourceScanOptions;

import java.nio.file.Path;
import java.util.List;

// 作用：源码分析端口，输入源文件并输出分析结果。
// 使用场景：索引服务调用它，具体实现是 JavaSourceAnalyzer。

/**
 * Analyses a set of immutable source snapshots and returns a candidate graph.
 * Implementations must not publish to storage or manage worker processes here.
 */
public interface SourceAnalyzer {
    // 作用：分析源文件生成候选图；逻辑：解析语法与语义结构，输出方法、值节点与边。
    AnalysisResult analyze(List<SourceFileRecord> sourceFiles, Path projectRoot);

    // 作用：提供默认分析入口；逻辑：忽略扫描选项，直接调用两参数的分析方法。
    default AnalysisResult analyze(
            List<SourceFileRecord> sourceFiles,
            Path projectRoot,
            SourceScanOptions options
    ) {
        return analyze(sourceFiles, projectRoot);
    }
}
