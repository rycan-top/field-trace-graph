package io.fieldtracegraph.javaanalysis.extractor;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.javaanalysis.CallableScope;
import io.fieldtracegraph.javaanalysis.JavaAnalysisAccumulator;
import io.fieldtracegraph.javaanalysis.flow.CallableOwnership;
import io.fieldtracegraph.javaanalysis.flow.GuardConditionResolver;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.flow.NodeFlowOwnership;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 作用：提取顺序语句与变量覆盖关系。
// 使用场景：同一变量多次赋值时确定最终来源。

/**
 * Walks a callable as a sequential control-flow graph. It materializes PHI
 * nodes at joins and keeps the condition path that selected each input, which
 * is required for sequential assignments, setter writes, loops and try/catch.
 */
public final class SequentialFlowExtractor {
    private final ValueEdgeEmitter edgeEmitter = new ValueEdgeEmitter();

    // 作用：按顺序遍历可执行体；逻辑：先取方法或构造器语句块，再交给内部分析器合并分支状态并补充条件依赖边。
    public void extract(
            SourceFileRecord source,
            CallableScope scope,
            Map<String, ValueNodeRecord> nodes,
            JavaAnalysisAccumulator accumulator,
            JavaValueFlowContext context
    ) {
        BlockStmt body = bodyOf(scope.callable());
        if (body == null) {
            return;
        }
        Analysis analysis = new Analysis(source, scope, nodes, accumulator, context);
        analysis.analyzeStatements(body.getStatements(), new LinkedHashMap<>(), "", false);
        analysis.emitConditionUses();
    }

    // 作用：取出可执行体的语句块；逻辑：方法取可选方法体，构造器取构造体，其他声明返回空。
    private BlockStmt bodyOf(CallableDeclaration<?> callable) {
        if (callable instanceof MethodDeclaration method) {
            return method.getBody().orElse(null);
        }
        if (callable instanceof ConstructorDeclaration constructor) {
            return constructor.getBody();
        }
        return null;
    }

    // 作用：剥掉表达式外层的括号与强制类型转换；逻辑：循环解包直到得到真实表达式。
    private Expression unwrap(Expression expression) {
        Expression value = expression;
        while (value instanceof com.github.javaparser.ast.expr.EnclosedExpr enclosed
                || value instanceof com.github.javaparser.ast.expr.CastExpr) {
            value = value instanceof com.github.javaparser.ast.expr.EnclosedExpr enclosed
                    ? enclosed.getInner()
                    : ((com.github.javaparser.ast.expr.CastExpr) value).getExpression();
        }
        return value;
    }

    private final class Analysis {
        private final SourceFileRecord source;
        private final CallableScope scope;
        private final Map<String, ValueNodeRecord> nodes;
        private final JavaAnalysisAccumulator accumulator;
        private final JavaValueFlowContext context;
        private final GuardConditionResolver guards;
        private final Map<String, Integer> occurrence = new LinkedHashMap<>();
        private int statementOrdinal;

        // 作用：创建单个可执行体的流分析上下文；逻辑：保存输入并初始化守卫条件解析器。
        private Analysis(
                SourceFileRecord source,
                CallableScope scope,
                Map<String, ValueNodeRecord> nodes,
                JavaAnalysisAccumulator accumulator,
                JavaValueFlowContext context
        ) {
            this.source = source;
            this.scope = scope;
            this.nodes = nodes;
            this.accumulator = accumulator;
            this.context = context;
            this.guards = new GuardConditionResolver(scope.callable(), context);
        }

        // 作用：顺序分析一组语句；逻辑：复制输入状态，逐条分析并用上一条的输出作为下一条的输入。
        private Map<String, String> analyzeStatements(
                List<Statement> statements,
                Map<String, String> input,
                String pathCondition,
                boolean controlled
        ) {
            Map<String, String> state = new LinkedHashMap<>(input);
            for (Statement statement : statements) {
                state = analyzeStatement(statement, state, pathCondition, controlled);
            }
            return state;
        }

        // 作用：按语句类型分派分析逻辑；逻辑：控制流语句递归处理，普通语句记录流依赖、直接写入与返回。
        private Map<String, String> analyzeStatement(
                Statement statement,
                Map<String, String> input,
                String pathCondition,
                boolean controlled
        ) {
            if (statement instanceof BlockStmt block) {
                return analyzeStatements(block.getStatements(), input, pathCondition, controlled);
            }
            if (statement instanceof IfStmt branch) {
                return analyzeIf(branch, input, pathCondition);
            }
            if (statement instanceof SwitchStmt branch) {
                return analyzeSwitch(branch, input, pathCondition);
            }
            if (statement instanceof WhileStmt loop) {
                return analyzeLoop(loop, loop.getBody(), guards.conditionText(loop.getCondition()),
                        input, pathCondition, true);
            }
            if (statement instanceof ForStmt loop) {
                String condition = loop.getCompare().map(guards::conditionText).orElse("for-loop");
                return analyzeLoop(loop, loop.getBody(), condition, input, pathCondition, true);
            }
            if (statement instanceof ForEachStmt loop) {
                String condition = "hasNext(" + guards.conditionText(loop.getIterable()) + ")";
                return analyzeLoop(loop, loop.getBody(), condition, input, pathCondition, true);
            }
            if (statement instanceof DoStmt loop) {
                return analyzeLoop(loop, loop.getBody(), "do",
                        input, pathCondition, false);
            }
            if (statement instanceof TryStmt tryStatement) {
                return analyzeTry(tryStatement, input, pathCondition);
            }
            Map<String, String> state = new LinkedHashMap<>(input);
            recordStreamDependencies(statement, state);
            recordStraightLineDefinitions(statement, state, pathCondition, controlled);
            if (statement instanceof ReturnStmt returnStatement) {
                recordReturn(returnStatement, state, pathCondition, controlled);
            }
            return state;
        }

        // 作用：记录流式调用链的起点依赖；逻辑：识别终止操作，回溯链源并建立从源定义到调用结果的派生边。
        private void recordStreamDependencies(
                Statement statement,
                Map<String, String> state
        ) {
            for (MethodCallExpr call : statement.findAll(MethodCallExpr.class)) {
                if (!CallableOwnership.belongsToCallableOrAnonymousBody(call, scope.callable())
                        || !context.isStreamCall(call)
                        || context.isIntermediateStreamCall(call)) {
                    continue;
                }
                JavaValueFlowContext.StreamChain chain = context.streamChain(call).orElse(null);
                if (chain == null) {
                    continue;
                }
                String sourceKey = context.key(chain.source());
                String targetKey = context.key(call);
                if (sourceKey == null || sourceKey.isBlank()
                        || targetKey == null || targetKey.isBlank()) {
                    continue;
                }
                // A stream can start from a getter expression such as
                // result.getData(). The source state is keyed by the root
                // variable (result), not by the normalized field key
                // (result.data), so resolve the root definition before
                // falling back to the expression key.
                String definition = sourceDefinition(chain.source(), state, sourceKey);
                ValueNodeRecord sourceNode = edgeEmitter.ensure(
                        source, scope, nodes, accumulator, definition, call
                );
                ValueNodeRecord targetNode = edgeEmitter.ensure(
                        source, scope, nodes, accumulator, targetKey, call
                );
                String operation = guards.withCondition(
                        "receiver of stream chain",
                        guards.conditionFor(call)
                );
                edgeEmitter.addEdge(
                        source, scope, accumulator, sourceNode, targetNode,
                        "DERIVED_FROM", operation, call, statementOrdinal++
                );
            }
        }

        // 作用：解析流源表达式的实际定义；逻辑：逐层去方法调用、字段访问与数组下标，最终按根变量查当前状态。
        private String sourceDefinition(
                Expression sourceExpression,
                Map<String, String> state,
                String fallback
        ) {
            Expression current = unwrap(sourceExpression);
            while (current instanceof MethodCallExpr call && call.getScope().isPresent()) {
                current = unwrap(call.getScope().orElseThrow());
            }
            while (current instanceof com.github.javaparser.ast.expr.FieldAccessExpr field) {
                current = unwrap(field.getScope());
            }
            while (current instanceof com.github.javaparser.ast.expr.ArrayAccessExpr access) {
                current = unwrap(access.getName());
            }
            if (current instanceof com.github.javaparser.ast.expr.NameExpr name) {
                String definition = state.get(name.getNameAsString());
                if (definition != null && !definition.isBlank()) {
                    return definition;
                }
            }
            return fallback;
        }

        // 作用：分析条件分支；逻辑：分别以条件成立和取反条件分析两个分支，再在汇合点生成 PHI 状态。
        private Map<String, String> analyzeIf(
                IfStmt branch,
                Map<String, String> input,
                String pathCondition
        ) {
            String condition = guards.conditionText(branch.getCondition());
            String thenCondition = guards.combine(pathCondition, condition);
            String elseCondition = guards.combine(pathCondition, guards.negate(condition));
            Map<String, String> thenState = analyzeStatement(branch.getThenStmt(),
                    new LinkedHashMap<>(input), thenCondition, true);
            Map<String, String> elseState = branch.getElseStmt()
                    .map(statement -> analyzeStatement(statement, new LinkedHashMap<>(input),
                            elseCondition, true))
                    .orElseGet(() -> new LinkedHashMap<>(input));
            return mergeStates(branch, input,
                    List.of(new ConditionalState(thenState, thenCondition),
                            new ConditionalState(elseState, elseCondition)));
        }

        // 作用：分析 switch 分支；逻辑：为每个 case 构造选择条件并分析，缺少 default 时补充原状态路径。
        private Map<String, String> analyzeSwitch(
                SwitchStmt branch,
                Map<String, String> input,
                String pathCondition
        ) {
            String selector = guards.conditionText(branch.getSelector());
            List<ConditionalState> paths = new ArrayList<>();
            boolean hasDefault = false;
            for (SwitchEntry entry : branch.getEntries()) {
                String localCondition;
                if (entry.getLabels().isEmpty()) {
                    hasDefault = true;
                    localCondition = guards.combine(pathCondition, "default(" + selector + ")");
                } else {
                    String cases = entry.getLabels().stream()
                            .map(label -> selector + " == " + guards.conditionText(label))
                            .reduce((left, right) -> left + " || " + right)
                            .orElse(selector);
                    localCondition = guards.combine(pathCondition,
                            entry.getLabels().size() > 1 ? "(" + cases + ")" : cases);
                }
                Map<String, String> state = analyzeStatements(entry.getStatements(),
                        new LinkedHashMap<>(input), localCondition, true);
                paths.add(new ConditionalState(state, localCondition));
            }
            if (!hasDefault) {
                paths.add(new ConditionalState(new LinkedHashMap<>(input), pathCondition));
            }
            return mergeStates(branch, input, paths);
        }

        // 作用：分析循环体；逻辑：以循环条件分析一遍循环体，并按是否可能跳过循环加入原状态路径。
        private Map<String, String> analyzeLoop(
                Node owner,
                Statement body,
                String condition,
                Map<String, String> input,
                String pathCondition,
                boolean maySkip
        ) {
            String bodyCondition = guards.combine(pathCondition, condition);
            Map<String, String> bodyState = analyzeStatement(body, new LinkedHashMap<>(input),
                    bodyCondition, true);
            List<ConditionalState> paths = new ArrayList<>();
            paths.add(new ConditionalState(bodyState, bodyCondition));
            if (maySkip) {
                paths.add(new ConditionalState(new LinkedHashMap<>(input),
                        guards.combine(pathCondition, guards.negate(condition))));
            }
            return mergeStates(owner, input, paths);
        }

        // 作用：分析 try/catch/finally；逻辑：分别分析 try 与各 catch 后合并，再顺序叠加 finally 语句。
        private Map<String, String> analyzeTry(
                TryStmt statement,
                Map<String, String> input,
                String pathCondition
        ) {
            List<ConditionalState> paths = new ArrayList<>();
            String tryCondition = guards.combine(pathCondition, "try");
            paths.add(new ConditionalState(analyzeStatements(statement.getTryBlock().getStatements(),
                    new LinkedHashMap<>(input), tryCondition, true), tryCondition));
            for (var catchClause : statement.getCatchClauses()) {
                String catchCondition = guards.combine(pathCondition,
                        "catch(" + catchClause.getParameter().getType().asString() + ")");
                paths.add(new ConditionalState(analyzeStatements(catchClause.getBody().getStatements(),
                        new LinkedHashMap<>(input), catchCondition, true), catchCondition));
            }
            Map<String, String> merged = mergeStates(statement, input, paths);
            if (statement.getFinallyBlock().isEmpty()) {
                return merged;
            }
            return analyzeStatements(statement.getFinallyBlock().orElseThrow().getStatements(),
                    merged, guards.combine(pathCondition, "finally"), false);
        }

        // 作用：合并多条控制流路径的状态；逻辑：对发生变化的变量生成 PHI 节点，并记录每条路径输入及条件。
        private Map<String, String> mergeStates(
                Node owner,
                Map<String, String> input,
                List<ConditionalState> paths
        ) {
            Map<String, String> merged = new LinkedHashMap<>(input);
            Set<String> targets = new LinkedHashSet<>(input.keySet());
            for (ConditionalState path : paths) {
                targets.addAll(path.state().keySet());
            }
            for (String target : targets) {
                LinkedHashSet<String> definitions = new LinkedHashSet<>();
                for (ConditionalState path : paths) {
                    String definition = path.state().get(target);
                    definitions.add(definition == null ? input.get(target) : definition);
                }
                definitions.remove(null);
                boolean changed = paths.stream()
                        .map(path -> path.state().get(target))
                        .anyMatch(definition -> definition != null
                                && !definition.equals(input.get(target)));
                if (!changed) {
                    continue;
                }
                String controlKey = occurrenceKey(owner);
                String phiKey = "phi:" + target + "@" + controlKey;
                ValueNodeRecord phi = edgeEmitter.ensure(source, scope, nodes, accumulator, phiKey, owner);
                String beforeKey = "before:" + target + "@" + controlKey;
                for (ConditionalState path : paths) {
                    String definition = path.state().get(target);
                    if (definition == null) {
                        definition = input.get(target);
                    }
                    if (definition == null) {
                        definition = beforeKey;
                    }
                    if (definition.equals(phiKey)) {
                        continue;
                    }
                    ValueNodeRecord origin = edgeEmitter.ensure(
                            source, scope, nodes, accumulator, definition, owner
                    );
                    addConditionalEdge(origin, phi, "PHI_INPUT", path.condition(), owner);
                }
                ValueNodeRecord materialized = edgeEmitter.ensure(
                        source, scope, nodes, accumulator, target, owner
                );
                edgeEmitter.addEdge(source, scope, accumulator, phi, materialized,
                        "ASSIGNED_FROM", "branch merge", owner, statementOrdinal++);
                // Keep the merge result as the current definition. Later
                // reads (including fluent stream receivers) must bind to the
                // PHI version rather than to an older bare variable.
                merged.put(target, phiKey);
            }
            return merged;
        }

        // 作用：记录直线代码中的定义；逻辑：按出现顺序处理变量声明、赋值与 setter 写入，并更新当前定义状态。
        private void recordStraightLineDefinitions(
                Statement statement,
                Map<String, String> state,
                String pathCondition,
                boolean controlled
        ) {
            List<Node> writes = directWrites(statement);
            for (Node write : writes) {
                if (write instanceof VariableDeclarator variable && variable.getInitializer().isPresent()) {
                    String target = context.applyAlias(variable.getNameAsString());
                    String branchTarget = branchTarget(target, variable, controlled);
                    statementOrdinal = edgeEmitter.emit(source, scope, nodes, accumulator, context,
                            variable.getInitializer().orElseThrow(), branchTarget, false, variable,
                            statementOrdinal, pathCondition);
                    state.put(target, controlled ? branchTarget : target);
                } else if (write instanceof AssignExpr assignment) {
                    String target = context.key(assignment.getTarget());
                    String branchTarget = branchTarget(target, assignment, controlled);
                    statementOrdinal = edgeEmitter.emit(source, scope, nodes, accumulator, context,
                            assignment.getValue(), branchTarget,
                            assignment.getTarget() instanceof com.github.javaparser.ast.expr.FieldAccessExpr,
                            assignment, statementOrdinal, pathCondition);
                    state.put(target, controlled ? branchTarget : target);
                } else if (write instanceof MethodCallExpr call
                        && context.isSetter(call)
                        && call.getArguments().size() == 1) {
                    String fieldPath = context.writeFieldPath(call);
                    String target = "field-write:" + fieldPath;
                    String branchTarget = branchTarget(target, call, controlled);
                    statementOrdinal = edgeEmitter.emit(source, scope, nodes, accumulator, context,
                            call.getArgument(0), branchTarget, true, call,
                            statementOrdinal, pathCondition);
                    state.put("field-write:" + fieldPath, controlled ? branchTarget : target);
                }
            }
        }

        // 作用：找出语句中直接发生的写入；逻辑：收集外部可执行体内的变量声明、赋值与 setter 调用并按位置排序。
        private List<Node> directWrites(Statement statement) {
            List<Node> writes = new ArrayList<>();
            if (statement instanceof ExpressionStmt expressionStatement
                    && expressionStatement.getExpression() instanceof VariableDeclarationExpr declaration) {
                declaration.getVariables().stream()
                        .filter(variable -> NodeFlowOwnership.belongsToOuterExecutable(
                                variable, scope.callable()))
                        .forEach(writes::add);
            }
            for (AssignExpr assignment : statement.findAll(AssignExpr.class)) {
                if (NodeFlowOwnership.belongsToOuterExecutable(assignment, scope.callable())) {
                    writes.add(assignment);
                }
            }
            for (MethodCallExpr call : statement.findAll(MethodCallExpr.class)) {
                if (context.isSetter(call)
                        && NodeFlowOwnership.belongsToOuterExecutable(call, scope.callable())) {
                    writes.add(call);
                }
            }
            writes.sort(Comparator
                    .comparingInt((Node node) -> node.getBegin().map(position -> position.line).orElse(0))
                    .thenComparingInt(node -> node.getBegin().map(position -> position.column).orElse(0)));
            return writes;
        }

        // 作用：为分支内写入生成带位置限定的目标键；逻辑：受控分支追加出现序号，直线代码保持原目标。
        private String branchTarget(String target, Node node, boolean controlled) {
            return controlled ? "branch:" + target + "@" + occurrenceKey(node) : target;
        }

        // 作用：记录返回语句的数据流；逻辑：把返回值表达式发射到统一返回节点，并在状态中标记已返回。
        private void recordReturn(
                ReturnStmt statement,
                Map<String, String> state,
                String pathCondition,
                boolean controlled
        ) {
            if (statement.getExpression().isEmpty()
                    || !NodeFlowOwnership.belongsToOuterExecutable(statement, scope.callable())) {
                return;
            }
            statementOrdinal = edgeEmitter.emit(source, scope, nodes, accumulator, context,
                    statement.getExpression().orElseThrow(), "RETURN", false,
                    statement, statementOrdinal, pathCondition);
            state.put("RETURN", "RETURN");
        }

        // 作用：追加一条带条件的 PHI 输入边；逻辑：根据锚点位置计算稳定 ID 与证据 ID，并登记数据边。
        private void addConditionalEdge(
                ValueNodeRecord origin,
                ValueNodeRecord target,
                String edgeType,
                String condition,
                Node anchor
        ) {
            int line = anchor.getBegin().map(position -> position.line).orElse(1);
            int column = anchor.getBegin().map(position -> position.column).orElse(1);
            String evidenceId = io.fieldtracegraph.core.support.Ids.sha256(
                    scope.symbolId(), origin.valueId(), target.valueId(), edgeType,
                    String.valueOf(line), String.valueOf(column)
            );
            accumulator.addDataEdge(new io.fieldtracegraph.core.model.DataEdgeRecord(
                    io.fieldtracegraph.core.support.Ids.sha256(
                            scope.symbolId(), origin.valueId(), target.valueId(), edgeType,
                            String.valueOf(line), String.valueOf(column), String.valueOf(statementOrdinal)
                    ),
                    scope.symbolId(),
                    origin.valueId(),
                    target.valueId(),
                    edgeType,
                    condition,
                    "EXACT_STATIC",
                    evidenceId,
                    io.fieldtracegraph.core.support.Ids.sha256(
                            scope.symbolId(), "phi", condition, String.valueOf(line), String.valueOf(column)),
                    condition,
                    source.fileId(),
                    line,
                    column,
                    anchor.getEnd().map(position -> position.line).orElse(line),
                    anchor.getEnd().map(position -> position.column).orElse(column),
                    statementOrdinal++
            ));
        }

        // 作用：生成节点位置的唯一出现键；逻辑：用行列坐标加同位置递增序号区分重复出现的节点。
        private String occurrenceKey(Node node) {
            int line = node.getBegin().map(position -> position.line).orElse(0);
            int column = node.getBegin().map(position -> position.column).orElse(0);
            int sequence = occurrence.merge(line + ":" + column, 1, Integer::sum);
            return line + ":" + column + ":" + sequence;
        }

        // 作用：补全所有控制条件的依赖；逻辑：遍历 if、循环及 switch 条件，逐个发射条件使用边。
        private void emitConditionUses() {
            for (IfStmt statement : scope.callable().findAll(IfStmt.class)) {
                if (CallableOwnership.belongsToCallableOrAnonymousBody(statement, scope.callable())) {
                    emitConditionUses(statement.getCondition(), statement);
                }
            }
            for (WhileStmt statement : scope.callable().findAll(WhileStmt.class)) {
                if (CallableOwnership.belongsToCallableOrAnonymousBody(statement, scope.callable())) {
                    emitConditionUses(statement.getCondition(), statement);
                }
            }
            for (DoStmt statement : scope.callable().findAll(DoStmt.class)) {
                if (CallableOwnership.belongsToCallableOrAnonymousBody(statement, scope.callable())) {
                    emitConditionUses(statement.getCondition(), statement);
                }
            }
            for (ForStmt statement : scope.callable().findAll(ForStmt.class)) {
                if (CallableOwnership.belongsToCallableOrAnonymousBody(statement, scope.callable())) {
                    statement.getCompare().ifPresent(compare -> emitConditionUses(compare, statement));
                }
            }
            for (SwitchStmt statement : scope.callable().findAll(SwitchStmt.class)) {
                if (CallableOwnership.belongsToCallableOrAnonymousBody(statement, scope.callable())) {
                    emitConditionUses(statement.getSelector(), statement);
                }
            }
            for (SwitchExpr statement : scope.callable().findAll(SwitchExpr.class)) {
                if (CallableOwnership.belongsToCallableOrAnonymousBody(statement, scope.callable())) {
                    emitConditionUses(statement.getSelector(), statement);
                }
            }
        }

        // 作用：发射单个条件的依赖边；逻辑：先确保条件节点存在，再把条件中引用的各来源连到条件节点。
        private void emitConditionUses(Expression condition, Node anchor) {
            String conditionKey = context.key(condition);
            if (conditionKey.isBlank()) {
                return;
            }
            ValueNodeRecord conditionNode = edgeEmitter.ensure(
                    source, scope, nodes, accumulator, conditionKey, anchor
            );
            for (ExpressionFlowSources.Source operand : ExpressionFlowSources.collect(condition, context)) {
                ValueNodeRecord origin = edgeEmitter.ensure(
                        source, scope, nodes, accumulator, operand.key(), condition
                );
                if (!origin.valueId().equals(conditionNode.valueId())) {
                    edgeEmitter.addEdge(source, scope, accumulator, origin, conditionNode,
                            "CONDITION_USES", "control condition", condition, statementOrdinal++);
                }
            }
        }
    }

    private record ConditionalState(Map<String, String> state, String condition) {
    }
}
