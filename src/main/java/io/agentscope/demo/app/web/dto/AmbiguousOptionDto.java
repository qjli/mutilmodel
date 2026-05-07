package io.agentscope.demo.app.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/** One candidate value when the model is unsure which option matches a form field. */
public class AmbiguousOptionDto {

    @JsonProperty("option_id")
    public String optionId;

    @JsonProperty("label")
    public String label;

    @JsonProperty("suggested_value")
    public String suggestedValue;

    public AmbiguousOptionDto() {}

    public AmbiguousOptionDto(String optionId, String label, String suggestedValue) {
        this.optionId = optionId;
        this.label = label;
        this.suggestedValue = suggestedValue;
    }

    public String safeSuggestedValue() {
        return suggestedValue != null && !suggestedValue.isBlank() ? suggestedValue : label;
    }

    public String safeLabel() {
        return label != null && !label.isBlank() ? label : Objects.toString(optionId, "");
    }
}
