package io.agentscope.demo.app.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured output for multimodal form filling: partial field map plus optional ambiguity
 * branches.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FormVisionExtraction {

    @JsonProperty("form_patch")
    public Map<String, Object> formPatch = new LinkedHashMap<>();

    @JsonProperty("ambiguities")
    public List<AmbiguousFieldDto> ambiguities = List.of();

    @JsonProperty("reply")
    public String reply;

    public FormVisionExtraction() {}
}
