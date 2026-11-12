package io.fieldtracegraph.cli.project;

import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.SourceScanOptions;
import io.fieldtracegraph.core.port.ProjectSourceReader;

import java.nio.file.Path;
import java.util.List;

// 作用：本地项目源码采集实现。
// 使用场景：index 流程读取源码文件并生成记录。

/**
 * Local filesystem implementation of the project source reader. It keeps all
 * scanning and path-policy concerns inside the CLI adapter layer.
 */
public final class LocalProjectSourceReader implements ProjectSourceReader {
    private final SourceFileScanner sourceScanner;

    // 作用：注入源文件扫描器；逻辑：保存扫描器供采集方法复用。
    public LocalProjectSourceReader(SourceFileScanner sourceScanner) {
        this.sourceScanner = sourceScanner;
    }

    // 作用：采集源文件快照；逻辑：委托扫描器按项目根与扫描选项执行遍历。
    @Override
    public List<SourceFileRecord> capture(Path projectRoot, SourceScanOptions options) {
        return sourceScanner.scan(projectRoot, options);
    }
}
