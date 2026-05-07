package io.agentscope.demo.app.web;

import io.agentscope.demo.app.service.DemoChatService;
import io.agentscope.demo.app.web.dto.ChatRequest;
import io.agentscope.demo.app.web.dto.ChatResponse;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
@Validated
public class ChatController {

    private final DemoChatService demoChatService;

    public ChatController(DemoChatService demoChatService) {
        this.demoChatService = demoChatService;
    }

    @PostMapping("/{sessionId}/messages")
    public ChatResponse send(
            @PathVariable String sessionId, @Valid @RequestBody ChatRequest body) {
        return demoChatService.chat(sessionId, body);
    }
}
