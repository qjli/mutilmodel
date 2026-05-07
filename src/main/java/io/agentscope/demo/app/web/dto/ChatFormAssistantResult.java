package io.agentscope.demo.app.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

/** Structured output for console text chat: natural reply plus optional form field patch. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatFormAssistantResult {

    @JsonProperty("reply")
    public String reply;

    @JsonProperty("form_patch")
    public Map<String, Object> formPatch = new LinkedHashMap<>();

    public ChatFormAssistantResult() {}
}
