package io.agentscope.demo.app.web;

import io.agentscope.demo.app.service.FormVisionStreamService;
import java.util.List;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/sessions")
public class FormVisionController {

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
     * Multipart upload of multiple images; streams JSON events (progress, thinking, result) as
     * Server-Sent Events.
     */
    @PostMapping(
            value = "/{sessionId}/vision/form-stream",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter visionFormStream(
            @PathVariable String sessionId, @RequestPart("files") List<MultipartFile> files) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        agentscopeTaskExecutor.execute(() -> formVisionStreamService.runAnalysis(sessionId, files, emitter));
        return emitter;
    }
}
