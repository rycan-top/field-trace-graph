package io.fieldtracegraph.cli.dto;

import io.fieldtracegraph.core.model.CommandResult;
import io.fieldtracegraph.core.model.Diagnostic;
import io.fieldtracegraph.core.model.TraceResult;

import java.util.List;

// 作用：命令结果的 JSON 外壳结构。
// 使用场景：所有命令使用 --format json 时的统一输出。

public record CommandResultDto(
        int schemaVersion,
        String command,
        String status,
        boolean complete,
        Object data,
        List<Diagnostic> diagnostics
) {
    // 作用：把领域结果转为 JSON 外壳；逻辑：追踪结果改用专用 DTO，其余数据原样保留。
    public static CommandResultDto from(CommandResult result) {
        Object data = result.data() instanceof TraceResult trace ? TraceResultDto.from(trace) : result.data();
        return new CommandResultDto(
                result.schemaVersion(),
                result.command(),
                result.status(),
                result.complete(),
                data,
                List.copyOf(result.diagnostics())
        );
    }
}
