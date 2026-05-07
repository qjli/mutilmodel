package io.agentscope.demo.app.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多图视觉路径下 Agent 的结构化输出：表单补丁、歧义列表与一句中文摘要。
 *
 * <p>经 SSE {@code result} 事件原样序列化给前端；未知字段由 {@link JsonIgnoreProperties#ignoreUnknown()} 忽略。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FormVisionExtraction {

    /** 模型有把握的字段键值对（camelCase 键）。 */
    @JsonProperty("form_patch")
    public Map<String, Object> formPatch = new LinkedHashMap<>();

    /** 需用户在表单侧点选确认的字段；空列表表示无歧义。 */
    @JsonProperty("ambiguities")
    public List<AmbiguousFieldDto> ambiguities = List.of();

    /** 简短中文总结，展示在对话气泡主文案。 */
    @JsonProperty("reply")
    public String reply;

    public FormVisionExtraction() {}
}
