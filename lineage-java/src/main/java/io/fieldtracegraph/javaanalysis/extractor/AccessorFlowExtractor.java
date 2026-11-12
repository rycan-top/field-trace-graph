package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.support.Ids;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.flow.NodeFlowOwnership;

import java.util.Map;

// 作用：提取 getter/setter 产生的字段值流。
// 使用场景：getX() 与 setX(v) 在调用链中传播字段值。

/**
 * Emits field-write and condition-use edges for accessor calls. Assignment and
 * return reads are emitted by {@link AssignmentFlowExtractor}; keeping accessor
 * mutation here prevents the general assignment extractor from growing into a
 * second all-purpose analyzer.
 */
public final class AccessorFlowExtractor {
    // 作用：提取 setter 写入流；逻辑：遍历目标作用域内的 setter 调用，把参数来源连到字段写入节点。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        int statementOrdinal = 0;
        for (MethodCallExpr call : scope.callable().findAll(MethodCallExpr.class)) {
            if (!CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())
                    || NodeFlowOwnership.belongsToOuterExecutable(call, scope.callable())
                    || !context.isSetter(call)) {
                continue;
            }
            String fieldPath = context.writeFieldPath(call);
            ValueNodeRecord target = nodes.get("field-write:" + fieldPath);
            if (target == null) {
                continue;
            }
            for (ExpressionFlowSources.Source input : ExpressionFlowSources.collect(call.getArgument(0), context)) {
                if ("BUILDER_RESULT".equals(input.edgeType())) {
                    // BuilderFlowExtractor maps builder properties directly to
                    // the receiving object's fields. Keeping the build() result
                    // as an object write only creates an internal target node.
                    continue;
                }
                ValueNodeRecord origin = nodes.get(input.key());
                if (origin == null || origin.valueId().equals(target.valueId())) {
                    continue;
                }
                addEdge(source, scope, origin, target, "FIELD_WRITE", call, accumulator, statementOrdinal++);
            }
        }
    }

    // 作用：写入一条字段写边；逻辑：用来源、目标与位置生成证据 ID，并追加精确静态数据边。
    private void addEdge(
            SourceFileRecord source,
            CallableScope scope,
            ValueNodeRecord origin,
            ValueNodeRecord target,
            String edgeType,
            Node evidence,
            JavaAnalysisAccumulator accumulator,
            int statementOrdinal
    ) {
        int line = evidence.getBegin().map(position -> position.line).orElse(1);
        int column = evidence.getBegin().map(position -> position.column).orElse(1);
        int endLine = evidence.getEnd().map(position -> position.line).orElse(line);
        int endColumn = evidence.getEnd().map(position -> position.column).orElse(column);
        String evidenceId = Ids.sha256(
                scope.symbolId(),
                origin.valueId(),
                target.valueId(),
                edgeType,
                String.valueOf(line),
                String.valueOf(column)
        );
        accumulator.addDataEdge(new DataEdgeRecord(
                Ids.sha256(evidenceId, String.valueOf(statementOrdinal)),
                scope.symbolId(),
                origin.valueId(),
                target.valueId(),
                edgeType,
                null,
                "EXACT_STATIC",
                evidenceId,
                null,
                null,
                source.fileId(),
                line,
                column,
                endLine,
                endColumn,
                statementOrdinal
        ));
    }
}
