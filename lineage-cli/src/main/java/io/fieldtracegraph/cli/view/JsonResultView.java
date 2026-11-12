package io.fieldtracegraph.cli.view;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fieldtracegraph.cli.dto.CommandResultDto;
import io.fieldtracegraph.core.model.CommandResult;

// 作用：输出稳定的 JSON 结构。
// 使用场景：CI、脚本与下游工具消费命令结果。

public class JsonResultView {
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 作用：渲染 JSON 结果；逻辑：把命令结果转 DTO 后以缩进格式序列化。
    public String render(CommandResult result) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(CommandResultDto.from(result));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to render JSON result", e);
        }
    }
}
