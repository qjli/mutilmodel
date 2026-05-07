package io.agentscope.demo.app.service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.StructuredOutputReminder;
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.demo.SessionIds;
import io.agentscope.demo.app.agent.FormVisionFillSkillSupport;
import io.agentscope.demo.app.web.dto.ChatFormAssistantResult;
import io.agentscope.demo.app.web.dto.ChatRequest;
import io.agentscope.demo.app.web.dto.ChatResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 控制台文本对话业务：每轮请求构建短生命周期 {@link ReActAgent}，结合 Skill、结构化输出与 {@link JsonSession}
 * 持久化。
 *
 * <p>与 {@link FormVisionStreamService} 共用同一 {@code form_vision_fill} 技能约定，保证表单字段键（camelCase）
 * 与前端一致。
 */
@Service
public class DemoChatService {

    private static final Logger log = LoggerFactory.getLogger(DemoChatService.class);

    private final JsonSession jsonSession;
    private final DashScopeChatModel chatDashScopeChatModel;

    public DemoChatService(
            JsonSession jsonSession,
            @Qualifier("chatDashScopeChatModel") DashScopeChatModel chatDashScopeChatModel) {
        this.jsonSession = jsonSession;
        this.chatDashScopeChatModel = chatDashScopeChatModel;
    }

    /**
     * 执行一轮对话：先 {@code loadIfExists} 恢复会话，再 {@code call} 阻塞等待模型，最后 {@code saveTo} 写回。
     *
     * @param sessionId 原始会话 id（将经安全校验）
     * @param request 用户输入正文
     * @return 自然语言 {@code reply} 与可选 {@code formPatch}；异常时降级为可读错误文案而非向外抛 HTTP 500
     */
    public ChatResponse chat(String sessionId, ChatRequest request) {
        String safeId = SessionIds.requireSafeSessionId(sessionId);
        String text = request.content().trim();

        try {
            Toolkit toolkit = new Toolkit();
            SkillBox skillBox = new SkillBox(toolkit);
            skillBox.registerSkill(FormVisionFillSkillSupport.load());

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("ConsoleChatAgent")
                            .sysPrompt(
                                    "你是企业工商与资质信息填报助手。用户通过文本描述需求时，应加载并遵循技能"
                                        + " **form_vision_fill** 中的字段键（camelCase）、日期与歧义规则。\n"
                                        + "在结构化输出中给出 reply（面向用户的自然语言中文说明）与 form_patch："
                                        + "form_patch 仅包含你根据本轮对话有把握推断的字段；不要编造证照号码或统一社会信用代码；"
                                        + "不确定的字段不要写入 form_patch。")
                            .model(chatDashScopeChatModel)
                            .toolkit(toolkit)
                            .skillBox(skillBox)
                            .memory(new InMemoryMemory())
                            .maxIters(8)
                            .structuredOutputReminder(StructuredOutputReminder.PROMPT)
                            .build();

            agent.loadIfExists(jsonSession, safeId);

            Msg userMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    TextBlock.builder()
                                            .text(text)
                                            .build())
                            .build();

            Msg response =
                    agent.call(userMsg, ChatFormAssistantResult.class)
                            .block(Duration.ofMinutes(3));
            agent.saveTo(jsonSession, safeId);

            if (response == null) {
                return new ChatResponse("模型未返回内容，请稍后重试。", null, Instant.now());
            }

            ChatFormAssistantResult structured = null;
            if (response.hasStructuredData()) {
                structured = response.getStructuredData(ChatFormAssistantResult.class);
            }

            String reply =
                    structured != null && structured.reply != null && !structured.reply.isBlank()
                            ? structured.reply
                            : response.getTextContent();
            if (reply == null || reply.isBlank()) {
                reply = "（无文本回复）";
            }

            Map<String, Object> patch = null;
            if (structured != null
                    && structured.formPatch != null
                    && !structured.formPatch.isEmpty()) {
                patch = structured.formPatch;
            }

            return new ChatResponse(reply, patch, Instant.now());
        } catch (IOException e) {
            log.error("failed to load skill, sessionId={}", safeId, e);
            return new ChatResponse("服务初始化失败：无法加载表单技能资源。", null, Instant.now());
        } catch (Exception e) {
            log.error("chat agent failed, sessionId={}", safeId, e);
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new ChatResponse("调用语言模型失败：" + detail, null, Instant.now());
        }
    }
}
