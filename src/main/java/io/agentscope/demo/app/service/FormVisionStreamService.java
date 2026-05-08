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

    /** 会话级 Agent 状态落盘，供 loadIfExists / saveTo 与文本对话共用同一 sessionId 目录。 */
    private final JsonSession jsonSession;
    /** 视觉模型（qwen-vl-max 流式），与文本对话用的 chat Bean 分离注入。 */
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
        final long t0 = System.nanoTime(); // 全链路耗时（读图 + 推理 + 落盘），成功路径末尾打 totalMs
        // 失败时 catch 要打原始 path 与已解析的 safeId；故在 try 外声明，成功路径里才会被赋值
        String safeId = "";
        try {
            // 防路径穿越与非法字符；后续 JsonSession 用 safeId 作为目录名
            safeId = SessionIds.requireSafeSessionId(sessionId);
            // 过滤掉空 part，避免前端误传占位项导致后续 Base64 为空
            List<MultipartFile> parts =
                    files.stream().filter(f -> f != null && !f.isEmpty()).toList();
            if (parts.isEmpty()) {
                log.warn(
                        "[vision] abort no usable images sessionId={} rawPartCount={}",
                        safeId,
                        files == null ? 0 : files.size());
                sendJson(emitter, Map.of("type", "error", "message", "请至少选择一张非空图片"));
                emitter.complete(); // 正常关闭 SSE，前端收到 error 帧后应结束监听
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
                // 每读完一张就推一条 progress，让 UI 进度条随读取前进（先于模型推理）
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
                imageBytes.add(p.getBytes()); // 全部进内存，避免模型侧再随机访问磁盘流
                mediaTypes.add(mediaTypeFor(p));
            }

            long totalImageBytes = imageBytes.stream().mapToLong(b -> b.length).sum();
            log.info(
                    "[vision] images loaded sessionId={} imageCount={} totalBytes={}",
                    safeId,
                    total,
                    totalImageBytes);

            // 进入模型前再推一条：phase 从 load_image 切到 infer，前端可切换文案/样式
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
            // DashScope 视觉输入：先一段任务说明（约束输出 JSON 形状），再按顺序追加 ImageBlock
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
                                                .mediaType(mediaTypes.get(i)) // 模型依赖正确 MIME 解码
                                                .build())
                                .build());
            }

            // 单条 USER 消息承载「文本指令 + N 张图」，模型在一次调用里综合全部图片
            Msg userMsg = Msg.builder().role(MsgRole.USER).content(blocks).build();

            Toolkit toolkit = new Toolkit();
            SkillBox skillBox = new SkillBox(toolkit);
            skillBox.registerSkill(FormVisionFillSkillSupport.load());

            // 本链路不要求跨轮对话记忆：每轮 vision 用干净 memory；会话持久化交给下面的 jsonSession
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

            // 若该 session 前期有过对话或识别，这里恢复 Agent 侧状态；无文件则 noop
            agent.loadIfExists(jsonSession, safeId);

            // —— 阶段 3：流式调用，映射 ReAct 事件到 SSE JSON——
            // 订阅 REASONING / SUMMARY / AGENT_RESULT：前者映射 thinking，后两者映射 assistant_text + 结构化
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

            // 流式过程中只要某条消息带上结构化体就写入；最后一次非空留在 ref 里供阶段 4 使用
            AtomicReference<FormVisionExtraction> structured = new AtomicReference<>();

            final long tInfer = System.nanoTime();
            log.info("[vision] agent.stream start sessionId={}", safeId);

            // blockLast：当前线程阻塞直到流结束或超时；因此必须由 Controller 侧线程池调用本方法
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
                                        // 有的实现只在 AGENT_RESULT 才挂 structuredData，故两处都判断
                                        if (event.getMessage().hasStructuredData()) {
                                            structured.set(
                                                    event.getMessage()
                                                            .getStructuredData(FormVisionExtraction.class));
                                        }
                                    }
                                } catch (IOException e) {
                                    // sendJson 失败（客户端断开等）：包成非受检异常让流终止并在 catch 外层统一收尾
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
            // 部分模型/版本在 stream 通道不附带完整 structured，同步 call 再要一次 FormVisionExtraction
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

            // 与前端约定：一次 JSON 内含 reply + formPatch + ambiguities，便于直接 setFieldsValue
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

            // 把本轮 Agent 状态写入 session 目录，供后续文本对话或其它请求复用
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
                // 尽量让浏览器收到 JSON error 事件后再关连接；若连接已死则忽略 IO 异常
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
            // 与 complete() 不同：向容器标记异常结束，便于监控与连接清理
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
        // 浏览器常把未知类型标成 octet-stream，此时按扩展名猜一种 image/*，减少模型解码失败
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

    /**
     * SSE 默认可能是字符串帧；显式指定 APPLICATION_JSON，Spring 用 Jackson 序列化 Map，
     * 前端可直接对 data 做 JSON.parse。
     */
    private void sendJson(SseEmitter emitter, Map<String, ?> payload) throws IOException {
        emitter.send(SseEmitter.event().data(payload, MediaType.APPLICATION_JSON));
    }
}
