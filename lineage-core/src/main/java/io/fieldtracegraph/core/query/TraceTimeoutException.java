package io.fieldtracegraph.core.query;

// 作用：表示一次追踪在预算内没有完成。
// 使用场景：加载超宽调用锥或物化大量证据时中止查询，并给出可识别的原因码。

public final class TraceTimeoutException extends RuntimeException {
    private final String phase;

    // 作用：记录超时阶段；逻辑：消息固定以 TRACE_TIMEOUT 开头，便于 CLI 与批量统计识别。
    public TraceTimeoutException(String phase) {
        super("TRACE_TIMEOUT: exceeded the query budget during " + phase);
        this.phase = phase;
    }

    // 作用：返回超时发生的阶段；逻辑：直接给出构造时记录的阶段名。
    public String phase() {
        return phase;
    }
}
