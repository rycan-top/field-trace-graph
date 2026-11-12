package io.fieldtracegraph.javaanalysis.mybatis;

import java.nio.file.Path;
import java.util.List;

// 作用：记录解析后的 Mapper 语句定义。
// 使用场景：承载 SQL、参数映射与结果映射信息。

public record MyBatisMapperDefinition(
        String namespace,
        Path xmlPath,
        String relativePath,
        String dialect,
        List<Statement> statements
) {
    public record Statement(
            String id,
            int line,
            String parameterType,
            List<ParameterReference> parameters,
            List<ForeachAlias> foreachAliases,
            String resultMap,
            String resultType,
            List<ResultMapping> resultMappings,
            List<BindAlias> bindAliases
    ) {
    }

    public record ParameterReference(
            String expression,
            int line,
            int columnStart,
            int lineEnd,
            int columnEnd,
            String syntax,
            boolean dynamic
    ) {
        // 作用：创建默认占位符参数引用；逻辑：使用 #{} 语法并标记为非动态。
        public ParameterReference(
                String expression,
                int line,
                int columnStart,
                int lineEnd,
                int columnEnd
        ) {
            this(expression, line, columnStart, lineEnd, columnEnd, "#{", false);
        }
    }

    public record ForeachAlias(
            String collection,
            String item,
            String index,
            int line,
            int columnStart
    ) {
    }

    public record ResultMapping(
            String column,
            String property,
            int line,
            int columnStart,
            boolean inferred
    ) {
        // 作用：创建非推断的结果映射；逻辑：把推断标记设为假并委托主构造器。
        public ResultMapping(String column, String property, int line, int columnStart) {
            this(column, property, line, columnStart, false);
        }
    }

    public record BindAlias(
            String name,
            String value,
            int line,
            int columnStart
    ) {
    }
}
