package io.agentscope.demo.app.web.dto;

import java.time.Instant;
import java.util.Map;

public record ChatResponse(
        String reply,
        /** Optional partial form values suggested by agent (merged client-side). */
        Map<String, Object> formPatch,
        Instant serverTime) {}
