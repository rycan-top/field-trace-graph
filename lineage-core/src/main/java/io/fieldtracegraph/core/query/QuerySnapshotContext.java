package io.fieldtracegraph.core.query;

// 作用：一次查询所需的快照、图与预算上下文。
// 使用场景：查询开始时创建，贯穿整个遍历生命周期。

/**
 * Request-scoped graph snapshot and budget. It is intentionally immutable and
 * never shared across requests.
 */
public record QuerySnapshotContext(LineageGraph graph, LineageQuery query) {
}
