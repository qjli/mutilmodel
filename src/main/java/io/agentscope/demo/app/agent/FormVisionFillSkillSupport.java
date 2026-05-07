package io.agentscope.demo.app.agent;

import io.agentscope.core.skill.AgentSkill;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Classpath skill shared by text chat and multimodal form vision flows. */
public final class FormVisionFillSkillSupport {

    private FormVisionFillSkillSupport() {}

    public static AgentSkill load() throws IOException {
        String md;
        try (InputStream in =
                Objects.requireNonNull(
                        FormVisionFillSkillSupport.class.getResourceAsStream("/skills/form_vision_fill.md"),
                        "classpath:/skills/form_vision_fill.md")) {
            md = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return AgentSkill.builder()
                .name("form_vision_fill")
                .description(
                        "Load when the user uploads business licenses or qualification scans and needs"
                                + " structured form fields (enterprise profile / permits). "
                                + "Defines canonical camelCase keys, date formats, and ambiguity rules.")
                .skillContent(md)
                .build();
    }
}
