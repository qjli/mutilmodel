package io.agentscope.demo.app.web;

import io.agentscope.demo.app.service.FormVisionStreamService;
import java.util.List;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 多图 + 表单结构化识别：以 SSE（{@code text/event-stream}）向客户端推送进度、思考片段与最终结果。
 *
 * <p>请求体为 {@code multipart/form-data}，字段名 {@code files} 可重复多次表示多图；服务端在独立线程中执行
 * {@link FormVisionStreamService#runAnalysis}，避免阻塞 Tomcat 工作线程。
 */
@RestController
@RequestMapping("/api/sessions")
public class FormVisionController {

    private static final Logger log = LoggerFactory.getLogger(FormVisionController.class);

    /** 单次 SSE 连接最长保持时间（毫秒），防止僵尸连接占满资源。 */
    private static final long SSE_TIMEOUT_MS = 30L * 60 * 1000;

    private final FormVisionStreamService formVisionStreamService;
    private final Executor agentscopeTaskExecutor;

    public FormVisionController(
            FormVisionStreamService formVisionStreamService,
            @Qualifier("agentscopeTaskExecutor") Executor agentscopeTaskExecutor) {
        this.formVisionStreamService = formVisionStreamService;
        this.agentscopeTaskExecutor = agentscopeTaskExecutor;
    }

    /**
     * 上传多张图片并流式返回 JSON 事件（{@code data: {...}\\n\\n}）：{@code progress}、{@code thinking}、
     * {@code assistant_text}、{@code result}、{@code done}、{@code error}。
     */
    @PostMapping(
            value = "/{sessionId}/vision/form-stream",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter visionFormStream(
            @PathVariable String sessionId, @RequestPart("files") List<MultipartFile> files) {
        int n = files == null ? 0 : files.size();
        log.info("[vision-sse] accepted pathSessionId={} multipartPartCount={}", sessionId, n);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        agentscopeTaskExecutor.execute(() -> formVisionStreamService.runAnalysis(sessionId, files, emitter));
        return emitter;
    }
}
