package io.fieldtracegraph.core.model;

import java.util.List;

// 作用：所有命令的统一结果外壳。
// 使用场景：text/JSON 渲染、成功失败包装与退出码判断。

public record CommandResult(
        int schemaVersion,
        String command,
        String status,
        boolean complete,
        Object data,
        List<Diagnostic> diagnostics
) {
    // 作用：构造成功结果；逻辑：状态标记为 ok，原样携带命令名与数据。
    public static CommandResult success(String command, Object data) {
        return new CommandResult(1, command, "OK", true, data, List.of());
    }

    // 作用：构造部分完成结果；逻辑：携带数据与诊断列表，供上层展示未完成原因。
    public static CommandResult partial(String command, Object data, List<Diagnostic> diagnostics) {
        return new CommandResult(1, command, "PARTIAL", false, data, diagnostics);
    }

    // 作用：构造失败结果；逻辑：写入错误码与错误消息，不携带业务数据。
    public static CommandResult failure(String command, String code, String message) {
        return new CommandResult(1, command, code, false, null, List.of(new Diagnostic(code, message, null, null, false, true)));
    }
}
