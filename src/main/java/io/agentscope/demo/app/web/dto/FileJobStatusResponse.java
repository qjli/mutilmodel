package io.agentscope.demo.app.web.dto;

import java.util.List;

public record FileJobStatusResponse(
        String jobId,
        String fileName,
        int percent,
        String phase,
        List<ParseStepView> steps) {}
