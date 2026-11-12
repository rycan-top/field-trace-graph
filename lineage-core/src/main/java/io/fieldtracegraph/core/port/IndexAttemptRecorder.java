package io.fieldtracegraph.core.port;

import io.fieldtracegraph.core.model.SourceFileRecord;

import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：索引尝试记录端口。
// 使用场景：索引失败或超时时留下可追踪的诊断记录。

public interface IndexAttemptRecorder {
    // 作用：登记一次索引尝试；逻辑：记录基线代数、待处理文件与删除集合，供失败追溯。
    void begin(
            String attemptId,
            int baseGeneration,
            List<SourceFileRecord> files,
            Map<String, String> previousHashes,
            Set<String> deletedPaths
    );

    // 作用：标记索引尝试失败；逻辑：写入错误码与错误信息，保留诊断线索。
    void markFailed(String attemptId, String errorCode, String errorMessage);
}
