package io.agentscope.demo.app.agent;

import io.agentscope.core.skill.AgentSkill;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从 classpath 加载「企业表单字段约定」技能 {@code form_vision_fill}，供文本对话与多图视觉两条链路复用。
 *
 * <p>技能正文位于 {@code /skills/form_vision_fill.md}，描述 camelCase 键名、日期格式与歧义输出规则，与前端
 * Ant Design Form 字段名对齐。本技能不包含 {@code upload_guide} 材料卡规则（由对话路由在适当时机单独加载其它技能）。
 */
public final class FormVisionFillSkillSupport {

    private static final Logger log = LoggerFactory.getLogger(FormVisionFillSkillSupport.class);

    private FormVisionFillSkillSupport() {}

    /**
     * 读取 Markdown 并构建 {@link AgentSkill}；由 {@link io.agentscope.core.skill.SkillBox#registerSkill} 注册到
     * 具体 Agent。
     *
     * @throws IOException 资源缺失或读取失败
     */
    public static AgentSkill load() throws IOException {
        String md;
        try (InputStream in =
                Objects.requireNonNull(
                        FormVisionFillSkillSupport.class.getResourceAsStream("/skills/form_vision_fill.md"),
                        "classpath:/skills/form_vision_fill.md")) {
            md = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        log.debug("[skill] loaded form_vision_fill markdownChars={}", md.length());
        return AgentSkill.builder()
                .name("form_vision_fill")
                .description(
                        "Full markdown is injected by the host on every vision/chat request that registers"
                                + " this skill; the model must not claim the document is unavailable. Load when the"
                                + " user uploads business licenses or qualification scans and needs structured form"
                                + " fields (enterprise profile / permits). Defines canonical camelCase keys, date"
                                + " formats, and ambiguity rules; use safety* vs transport* field groups for the two"
                                + " permit types.")
                .skillContent(md)
                .build();
    }
}
