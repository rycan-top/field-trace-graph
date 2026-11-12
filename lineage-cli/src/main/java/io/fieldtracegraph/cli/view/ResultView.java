package io.fieldtracegraph.cli.view;

import io.fieldtracegraph.core.model.CommandResult;

// 作用：结果输出门面，按格式选择具体视图。
// 使用场景：所有命令渲染 text 或 json。

/**
 * Thin dispatcher between the terminal and JSON renderers so controllers stay
 * free of formatting details.
 */
public class ResultView {
    private final TextResultView textResultView = new TextResultView();
    private final JsonResultView jsonResultView = new JsonResultView();

    // 作用：按格式渲染结果；逻辑：忽略大小写选择 JSON 或文本视图。
    public String render(CommandResult result, String format) {
        return "json".equalsIgnoreCase(format)
                ? jsonResultView.render(result)
                : textResultView.render(result);
    }
}
