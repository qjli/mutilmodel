package io.agentscope.demo.app.agent;

import io.agentscope.core.skill.AgentSkill;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从 classpath 加载「上传与材料引导」技能 {@code upload_guide_dialog}，指导用户如何上传、如何使用助手，
 * 并通过结构化 {@code upload_guide} 输出前端材料卡片。
 *
 * <p>正文位于 {@code /skills/upload_guide_dialog.md}；仅在用户<strong>文字对话</strong>中询问如何使用、如何上传、缺件或样例时由路由注册；与 {@link FormVisionFillSkillSupport} 职责分离，互不覆盖对方字段规则。
 */
public final class UploadGuideDialogSkillSupport {

    private static final Logger log = LoggerFactory.getLogger(UploadGuideDialogSkillSupport.class);

    private UploadGuideDialogSkillSupport() {}

    /**
     * @throws IOException 资源缺失或读取失败
     */
    public static AgentSkill load() throws IOException {
        String md;
        try (InputStream in =
                Objects.requireNonNull(
                        UploadGuideDialogSkillSupport.class.getResourceAsStream("/skills/upload_guide_dialog.md"),
                        "classpath:/skills/upload_guide_dialog.md")) {
            md = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        log.debug("[skill] loaded upload_guide_dialog markdownChars={}", md.length());
        return AgentSkill.builder()
                .name("upload_guide_dialog")
                .description(
                        "Load only on text-chat turns about how to use the page, how to upload, missing"
                                + " permit photos, or sample cards. Do not load for image-only extraction."
                                + " Defines upload_guide JSON; four sample_image_id values only.")
                .skillContent(md)
                .build();
    }
}
