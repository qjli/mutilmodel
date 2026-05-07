package io.agentscope.demo.app.service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
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
import io.agentscope.demo.app.web.dto.FormVisionExtraction;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 多图表单视觉分析：将图片读入内存、组装多模态 {@link Msg}，通过 {@link ReActAgent#stream} 推送 SSE 事件，
 * 最终产出 {@link FormVisionExtraction} 并写回 {@link JsonSession}。
 *
 * <p>事件协议与前端约定：{@code progress} / {@code thinking} / {@code assistant_text} / {@code result} /
 * {@code done} / {@code error}，每条为 JSON，封装在 SSE {@code data:} 帧内。
 */
@Service
public class FormVisionStreamService {

    private static final Logger log = LoggerFactory.getLogger(FormVisionStreamService.class);

    private final JsonSession jsonSession;
    private final DashScopeChatModel formVisionDashScopeChatModel;

    public FormVisionStreamService(
            JsonSession jsonSession,
            @Qualifier("formVisionDashScopeChatModel") DashScopeChatModel formVisionDashScopeChatModel) {
        this.jsonSession = jsonSession;
        this.formVisionDashScopeChatModel = formVisionDashScopeChatModel;
    }

    /**
     * 在调用线程之外应由异步执行器调用本方法：内部含阻塞式 {@link reactor.core.publisher.Flux#blockLast}。
     *
     * @param sessionId 原始会话 id（经 {@link SessionIds} 校验）
     * @param files 多部分文件列表（字段名 {@code files}）
     * @param emitter 已返回给客户端的 SSE 连接，用于逐步 {@code send} 与最终 {@code complete}
     */
    public void runAnalysis(String sessionId, List<MultipartFile> files, SseEmitter emitter) {
        final long t0 = System.nanoTime();
        String safeId = "";
        try {
            safeId = SessionIds.requireSafeSessionId(sessionId);
            List<MultipartFile> parts =
                    files.stream().filter(f -> f != null && !f.isEmpty()).toList();
            if (parts.isEmpty()) {
                log.warn(
                        "[vision] abort no usable images sessionId={} rawPartCount={}",
                        safeId,
                        files == null ? 0 : files.size());
                sendJson(emitter, Map.of("type", "error", "message", "请至少选择一张非空图片"));
                emitter.complete();
                return;
            }

            List<String> names = parts.stream()
                    .map(f -> f.getOriginalFilename() != null && !f.getOriginalFilename().isBlank()
                            ? f.getOriginalFilename()
                            : "image")
                    .toList();

            int total = parts.size();
            log.info("[vision] start sessionId={} imageCount={} fileNames={}", safeId, total, names);

            // —— 阶段 1：顺序读入各张图片，向前端报告「读取进度」——
            List<byte[]> imageBytes = new ArrayList<>();
            List<String> mediaTypes = new ArrayList<>();

            for (int i = 0; i < parts.size(); i++) {
                MultipartFile p = parts.get(i);
                sendJson(
                        emitter,
                        Map.of(
                                "type",
                                "progress",
                                "phase",
                                "load_image",
                                "done",
                                i + 1,
                                "total",
                                total,
                                "label",
                                "读取第 " + (i + 1) + " 张 / 共 " + total + " 张",
                                "fileName",
                                names.get(i)));
                imageBytes.add(p.getBytes());
                mediaTypes.add(mediaTypeFor(p));
            }

            long totalImageBytes = imageBytes.stream().mapToLong(b -> b.length).sum();
            log.info(
                    "[vision] images loaded sessionId={} imageCount={} totalBytes={}",
                    safeId,
                    total,
                    totalImageBytes);

            sendJson(
                    emitter,
                    Map.of(
                            "type",
                            "progress",
                            "phase",
                            "infer",
                            "done",
                            total,
                            "total",
                            total,
                            "label",
                            "正在调用视觉模型（含思考过程流式输出）…"));

            // —— 阶段 2：构造用户多模态消息（说明文字 + 多图 Base64）——
            List<ContentBlock> blocks = new ArrayList<>();
            blocks.add(
                    TextBlock.builder()
                            .text(
                                    "用户已上传 "
                                            + total
                                            + " 张图片（文件名供参考："
                                            + String.join("、", names)
                                            + "）。\n"
                                            + "请务必加载并遵循技能 **form_vision_fill** 中的字段键与歧义规则，"
                                            + "综合全部图片抽取与企业登记表单相关的信息。\n"
                                            + "仅输出结构化结果通道要求的 JSON（form_patch / ambiguities / reply），"
                                            + "日期与日期范围使用 ISO-8601 字符串。")
                            .build());
            for (int i = 0; i < imageBytes.size(); i++) {
                String b64 = Base64.getEncoder().encodeToString(imageBytes.get(i));
                blocks.add(
                        ImageBlock.builder()
                                .source(
                                        Base64Source.builder()
                                                .data(b64)
                                                .mediaType(mediaTypes.get(i))
                                                .build())
                                .build());
            }

            Msg userMsg = Msg.builder().role(MsgRole.USER).content(blocks).build();

            Toolkit toolkit = new Toolkit();
            SkillBox skillBox = new SkillBox(toolkit);
            skillBox.registerSkill(FormVisionFillSkillSupport.load());

            InMemoryMemory memory = new InMemoryMemory();
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("FormVisionAgent")
                            .sysPrompt(
                                    "你是企业资质与工商信息录入助手。当用户上传证照、许可证等影像时，"
                                            + "应使用技能 form_vision_fill 的字段约定进行抽取；输出为中文简述 + 结构化字段。")
                            .model(formVisionDashScopeChatModel)
                            .toolkit(toolkit)
                            .skillBox(skillBox)
                            .memory(memory)
                            .maxIters(12)
                            .structuredOutputReminder(StructuredOutputReminder.PROMPT)
                            .build();

            agent.loadIfExists(jsonSession, safeId);

            // —— 阶段 3：流式调用，映射 ReAct 事件到 SSE JSON——
            StreamOptions streamOpts =
                    StreamOptions.builder()
                            .eventTypes(
                                    EventType.REASONING,
                                    EventType.SUMMARY,
                                    EventType.AGENT_RESULT,
                                    EventType.HINT)
                            .incremental(true)
                            .includeReasoningChunk(true)
                            .includeReasoningResult(true)
                            .includeSummaryChunk(true)
                            .includeSummaryResult(true)
                            .build();

            AtomicReference<FormVisionExtraction> structured = new AtomicReference<>();

            final long tInfer = System.nanoTime();
            log.info("[vision] agent.stream start sessionId={}", safeId);

            agent.stream(List.of(userMsg), streamOpts, FormVisionExtraction.class)
                    .doOnNext(
                            event -> {
                                try {
                                    if (event.getType() == EventType.REASONING && event.getMessage() != null) {
                                        String delta = textOrEmpty(event.getMessage());
                                        if (!delta.isBlank()) {
                                            sendJson(
                                                    emitter,
                                                    Map.of("type", "thinking", "delta", delta));
                                        }
                                    }
                                    if ((event.getType() == EventType.SUMMARY
                                                    || event.getType() == EventType.AGENT_RESULT)
                                            && event.getMessage() != null) {
                                        String delta = textOrEmpty(event.getMessage());
                                        if (!delta.isBlank()) {
                                            sendJson(
                                                    emitter,
                                                    Map.of("type", "assistant_text", "delta", delta));
                                        }
                                        if (event.getMessage().hasStructuredData()) {
                                            structured.set(
                                                    event.getMessage()
                                                            .getStructuredData(FormVisionExtraction.class));
                                        }
                                    }
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            })
                    .blockLast(Duration.ofMinutes(12));

            long inferMs = (System.nanoTime() - tInfer) / 1_000_000L;
            log.info(
                    "[vision] agent.stream end sessionId={} inferMs={} hadStructuredInStream={}",
                    safeId,
                    inferMs,
                    structured.get() != null);

            // —— 阶段 4：若流结束仍无结构化体，再阻塞补一次 call（兜底）——
            FormVisionExtraction extraction = structured.get();
            if (extraction == null) {
                log.warn("[vision] structured absent after stream, fallback agent.call sessionId={}", safeId);
                Msg block = agent.call(userMsg, FormVisionExtraction.class).block(Duration.ofMinutes(8));
                if (block != null && block.hasStructuredData()) {
                    extraction = block.getStructuredData(FormVisionExtraction.class);
                }
            }

            if (extraction == null) {
                log.warn("[vision] structured still absent after fallback sessionId={}", safeId);
                extraction = new FormVisionExtraction();
                extraction.reply = "模型未返回可用的结构化结果，请稍后重试或检查图片清晰度。";
            }

            int patchKeys = extraction.formPatch == null ? 0 : extraction.formPatch.size();
            int ambN = extraction.ambiguities == null ? 0 : extraction.ambiguities.size();
            int replyChars = extraction.reply == null ? 0 : extraction.reply.length();
            log.info(
                    "[vision] emit result sessionId={} formPatchKeys={} ambiguities={} replyChars={}",
                    safeId,
                    patchKeys,
                    ambN,
                    replyChars);

            sendJson(
                    emitter,
                    Map.of(
                            "type",
                            "result",
                            "reply",
                            extraction.reply != null ? extraction.reply : "",
                            "formPatch",
                            extraction.formPatch != null ? extraction.formPatch : Map.of(),
                            "ambiguities",
                            extraction.ambiguities != null ? extraction.ambiguities : List.of()));

            agent.saveTo(jsonSession, safeId);
            sendJson(emitter, Map.of("type", "done"));
            emitter.complete();
            log.info(
                    "[vision] sse complete sessionId={} totalMs={}",
                    safeId,
                    (System.nanoTime() - t0) / 1_000_000L);
        } catch (Exception e) {
            log.error(
                    "[vision] failed pathSessionId={} safeSessionId={}",
                    sessionId,
                    safeId,
                    e);
            try {
                sendJson(
                        emitter,
                        Map.of(
                                "type",
                                "error",
                                "message",
                                e.getMessage() != null ? e.getMessage() : e.toString()));
            } catch (IOException ignored) {
                // 客户端已断开等情况：忽略二次发送失败
            }
            emitter.completeWithError(e);
        }
    }

    /** 安全读取 {@link Msg} 的纯文本聚合，避免 NPE。 */
    private static String textOrEmpty(Msg msg) {
        if (msg == null) {
            return "";
        }
        String t = msg.getTextContent();
        return t != null ? t : "";
    }

    /**
     * 为 {@link ImageBlock} 推断 MIME：优先 multipart 声明的 Content-Type，其次按扩展名回退，默认 {@code
     * image/jpeg}。
     */
    private static String mediaTypeFor(MultipartFile f) {
        String ct = f.getContentType();
        if (ct != null && !ct.isBlank() && !MediaType.APPLICATION_OCTET_STREAM_VALUE.equalsIgnoreCase(ct)) {
            return ct;
        }
        String name = f.getOriginalFilename();
        if (name == null) {
            return "image/jpeg";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/jpeg";
    }

    /** 以 {@code application/json} 作为 SSE 帧体编码，便于前端 {@code JSON.parse}。 */
    private void sendJson(SseEmitter emitter, Map<String, ?> payload) throws IOException {
        emitter.send(SseEmitter.event().data(payload, MediaType.APPLICATION_JSON));
    }
}
