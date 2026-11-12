package io.fieldtracegraph.core.port;

import java.nio.file.Path;

// 作用：只读图快照读取端口。
// 使用场景：trace 打开稳定视图，保证一次查询看到一致的 generation。

/**
 * Opens a fixed read-only snapshot for a project database. Implementations own
 * connection creation and the read transaction boundary.
 */
public interface GraphSnapshotReader {
    // 作用：打开只读图快照；逻辑：按数据库路径创建固定 generation 的查询会话。
    GraphReadSession open(Path databasePath);
}
