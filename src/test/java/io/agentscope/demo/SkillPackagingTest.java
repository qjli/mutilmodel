package io.agentscope.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.skill.util.SkillUtil;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class SkillPackagingTest {

    @Test
    void skillMdParsesViaSkillUtil() throws Exception {
        try (var in =
                Objects.requireNonNull(
                        getClass().getResourceAsStream("/skills/demo_echo_skill/SKILL.md"))) {
            String skillMd = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            AgentSkill skill = SkillUtil.createFrom(skillMd, Map.of());
            assertEquals("demo_echo_skill", skill.getName());
            assertFalse(skill.getDescription().isBlank());
        }
    }

    @Test
    void loadsPackagedSkillFromTestClasspath() throws Exception {
        try (ClasspathSkillRepository repo = new ClasspathSkillRepository("skills")) {
            assertTrue(repo.skillExists("demo_echo_skill"));
            AgentSkill skill = repo.getSkill("demo_echo_skill");
            assertEquals("demo_echo_skill", skill.getName());
        }
    }
}
