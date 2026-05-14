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
import io.agentscope.demo.app.agent.UploadGuideDialogSkillSupport;
import io.agentscope.demo.app.upload.MaterialSampleIds;
import io.agentscope.demo.app.upload.UploadGuideFromCoverage;
import io.agentscope.demo.app.upload.UploadMaterialCoverageStore;
import io.agentscope.demo.app.web.dto.ChatFormAssistantResult;
import io.agentscope.demo.app.web.dto.ChatRequest;
import io.agentscope.demo.app.web.dto.ChatResponse;
import io.agentscope.demo.app.web.dto.UploadGuideDto;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 控制台文本对话业务：每轮请求构建短生命周期 {@link ReActAgent}，结合 Skill、结构化输出与 {@link JsonSession}
 * 持久化。
 *
 * <p>与 {@link FormVisionStreamService} 共用 {@code form_vision_fill}；{@code upload_guide_dialog} 仅在文本对话命中
 * 「如何使用 / 缺件 / 上传说明」等意图时注册，与视觉链路解耦。
 */
@Service
public class DemoChatService {

    private static final Logger log = LoggerFactory.getLogger(DemoChatService.class);

    /**
     * 与技能 {@code upload_guide_dialog} 的「何时加载」对齐：仅在这些文字意图下注册该技能，避免与表单抽取技能交叉干扰。
     */
    private static final Pattern UPLOAD_GUIDE_DIALOG_INTENT =
            Pattern.compile(
                    "如何使用|怎么用|怎么上传|如何上传|使用说明|新手指南|第一次用|初次使用"
                            + "|本页|本页面|整页|页面上|页面.*上传|页面.*怎么|不会上传|咋上传|咋传|教我上传|上传教程|上传步骤"
                            + "|在哪上传|在哪里上传|上传入口|上传.*按钮|单选|多选|一次.*张|支持.*格式"
                            + "|缺什么|还缺|还需要|还要什么|还要哪些|缺哪些|缺哪|要准备|准备哪些|哪些材料|哪些证照|什么材料|什么照片|什么文件|哪些文件|传什么"
                            + "|样例图|样例|示意图|示范图|材料清单"
                            + "|能做什么|有什么用|本助手\\s*能",
                    Pattern.CASE_INSENSITIVE);

    /**
     * 用户追问「还缺哪些文件」等：须结合 {@link UploadMaterialCoverageStore} 仅展示仍缺项，四类齐备则不再出卡。
     */
    private static final Pattern REMAINING_FILES_INTENT =
            Pattern.compile(
                    "还需要什么|还需要哪些|还缺什么|还缺哪些|还要上传什么|还要传什么|还要准备什么|"
                            + "没传哪些|没上传哪些|剩下哪些|尚缺哪些|仍缺哪些|缺哪些|"
                            + "需要什么文件|需要哪些文件|要什么文件|"
                            + "哪些(图片|照片|文件|材料|证照).*(还没|尚未|没有)|"
                            + "哪些还没(传|上传)|要(准备|补传)哪些",
                    Pattern.CASE_INSENSITIVE);

    private final JsonSession jsonSession;
    private final DashScopeChatModel chatDashScopeChatModel;
    private final UploadMaterialCoverageStore uploadMaterialCoverageStore;

    public DemoChatService(
            JsonSession jsonSession,
            @Qualifier("chatDashScopeChatModel") DashScopeChatModel chatDashScopeChatModel,
            UploadMaterialCoverageStore uploadMaterialCoverageStore) {
        this.jsonSession = jsonSession;
        this.chatDashScopeChatModel = chatDashScopeChatModel;
        this.uploadMaterialCoverageStore = uploadMaterialCoverageStore;
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
        final long t0 = System.nanoTime();

        log.info("[chat] start sessionId={} contentChars={}", safeId, text.length());

        final boolean uploadGuideIntent = UPLOAD_GUIDE_DIALOG_INTENT.matcher(text).find();
        final boolean remainingFilesIntent = REMAINING_FILES_INTENT.matcher(text).find();

        try {
            Toolkit toolkit = new Toolkit();
            SkillBox skillBox = new SkillBox(toolkit);
            skillBox.registerSkill(FormVisionFillSkillSupport.load());
            if (uploadGuideIntent) {
                skillBox.registerSkill(UploadGuideDialogSkillSupport.load());
            }

            String sysPrompt =
                    "你是企业工商与资质信息填报助手，结构化输出类型为 ChatFormAssistantResult（含"
                            + " reply、form_patch、upload_guide）。\n"
                            + "1）表单字段与歧义：技能 **form_vision_fill** 全文已由应用注入本对话；你必须始终按其"
                            + " camelCase 键、日期 ISO、ambiguities 与 reply 章节骨架执行。**禁止**在思考过程或回复中声称"
                            + "无法加载、未读或未提供该技能文档。form_patch 仅写有把握字段，勿编造证照号与统一社会信用代码；"
                            + "两类行政许可字段须用技能中的 `safety*` 与 `transport*` 前缀，勿自造 issuingAuthority、"
                            + "issueDate、validityPeriodStart 等未在技能列出的键。\n";
            if (uploadGuideIntent) {
                Set<String> coverage = uploadMaterialCoverageStore.readMerged(safeId);
                if (remainingFilesIntent) {
                    String present = formatCoverageLabels(coverage);
                    String absent = formatMissingLabels(coverage);
                    sysPrompt +=
                            "2）本轮为**仍缺哪些材料/文件**类追问，已加载 **upload_guide_dialog**。\n"
                                    + "【本会话材料覆盖推断】来自历次「上传文件」的文件名关键词累计（非图像内容识别）："
                                    + "已推断出现：" + present + "；尚未推断出现：" + absent + "。\n"
                                    + "请据此用 **reply** 清晰说明仍缺证照的中文全称（仅列「尚未推断出现」中的项）；"
                                    + "若四类均已推断出现，则说明无需再补充上传，并勿引导「见下方材料卡片」。\n"
                                    + "结构化 **upload_guide** 将由应用与上述推断对齐，你可填占位或与推断一致；"
                                    + "证照名仅允许四种全称：营业执照、身份证人像面、道路危险货物运输许可证、危险化学品经营许可证。\n"
                                    + "两技能分工：form_patch 只来自 form_vision_fill；upload_guide 形状由应用与推断对齐。\n";
                } else {
                    sysPrompt +=
                            "2）本轮用户文字涉及如何使用本页、如何上传、缺哪些证照/材料或样例示意：已加载技能"
                                    + " **upload_guide_dialog**，仅按其规则填写 **upload_guide** 及 reply 中的操作说明。"
                                    + "泛泛问「如何使用」「怎么上传」「需要什么材料」等时 **upload_guide 不得为 null**："
                                    + "missing_items 须恰好四种 sample_image_id 各一条（顺序不限），并提醒卡片配图为样图示意非真实证照；"
                                    + "仅当用户明确只问某一已填字段、与上传材料无关时，upload_guide 可为 null。\n"
                                    + "列举须上传或仍缺的影像时仅允许四种：营业执照、身份证人像面、道路危险货物运输许可证、"
                                    + "危险化学品经营许可证；禁止组织机构代码证、税务登记证、「如适用」式发散；证照名禁止"
                                    + "「（如适用）」等括号后缀。\n"
                                    + "两技能分工：form_patch/ambiguities 只来自 form_vision_fill；upload_guide 只来自"
                                    + " upload_guide_dialog，勿混用规则。\n";
                }
            } else {
                sysPrompt +=
                        "2）本轮**未**加载 **upload_guide_dialog**。**upload_guide 必须为 null**；不要援引其材料卡"
                                + "规则；reply 勿引导「见下方材料卡片」类表述（无卡片）。\n";
            }
            sysPrompt +=
                    "reply 禁止 Markdown 图片语法与任何 http(s) 占位链接（如 example.com）。\n"
                            + (uploadGuideIntent
                                    ? "样例缩略图仅由 upload_guide 卡片展示。\n"
                                    : "");

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("ConsoleChatAgent")
                            .sysPrompt(sysPrompt)
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
                log.warn(
                        "[chat] empty model response sessionId={} elapsedMs={}",
                        safeId,
                        (System.nanoTime() - t0) / 1_000_000L);
                return new ChatResponse("模型未返回内容，请稍后重试。", null, Instant.now(), null);
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

            UploadGuideDto uploadGuide = structured != null ? structured.uploadGuide : null;
            if (!uploadGuideIntent) {
                uploadGuide = null;
            } else if (remainingFilesIntent) {
                uploadGuide = UploadGuideFromCoverage.buildRemaining(uploadMaterialCoverageStore.readMerged(safeId));
            }

            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            log.info(
                    "[chat] ok sessionId={} elapsedMs={} replyChars={} formPatchKeys={} uploadGuideIntent={} remainingFilesIntent={} uploadGuidePresent={}",
                    safeId,
                    elapsedMs,
                    reply.length(),
                    patch != null ? patch.size() : 0,
                    uploadGuideIntent,
                    remainingFilesIntent,
                    uploadGuide != null);

            return new ChatResponse(reply, patch, Instant.now(), uploadGuide);
        } catch (IOException e) {
            log.error("[chat] skill load failed sessionId={}", safeId, e);
            String hint =
                    uploadGuideIntent
                            ? "服务初始化失败：无法加载技能资源（表单字段约定或上传引导）。"
                            : "服务初始化失败：无法加载技能资源（表单字段约定）。";
            return new ChatResponse(hint, null, Instant.now(), null);
        } catch (Exception e) {
            log.error(
                    "[chat] agent failed sessionId={} elapsedMs={}",
                    safeId,
                    (System.nanoTime() - t0) / 1_000_000L,
                    e);
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new ChatResponse("调用语言模型失败：" + detail, null, Instant.now(), null);
        }
    }

    private static String formatCoverageLabels(Set<String> coverageIds) {
        if (coverageIds == null || coverageIds.isEmpty()) {
            return "（尚无）";
        }
        List<String> zh = new ArrayList<>();
        for (String id : MaterialSampleIds.CANONICAL_ORDER) {
            if (coverageIds.contains(id)) {
                zh.add(MaterialSampleIds.chineseTitle(id));
            }
        }
        return zh.isEmpty() ? "（尚无）" : String.join("、", zh);
    }

    private static String formatMissingLabels(Set<String> coverageIds) {
        List<String> miss = new ArrayList<>();
        for (String id : MaterialSampleIds.CANONICAL_ORDER) {
            if (coverageIds == null || !coverageIds.contains(id)) {
                miss.add(MaterialSampleIds.chineseTitle(id));
            }
        }
        return String.join("、", miss);
    }
}
