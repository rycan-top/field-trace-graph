package io.fieldtracegraph.core.query;

// 作用：标识跨调用时的行进方向。
// 使用场景：区分进入被调方法与回到调用方两种遍历动作。

/**
 * Describes how a query-time call bridge changes the active call-site context.
 * The context prevents a callee value shared by two calls from connecting the
 * wrong caller arguments.
 */
enum CallContextDirection {
    NONE,
    ENTER,
    LEAVE
}
