package io.agentscope.demo.app.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/** A form field where multiple readings are plausible; user picks in the UI. */
public class AmbiguousFieldDto {

    @JsonProperty("field_key")
    public String fieldKey;

    @JsonProperty("question_for_user")
    public String questionForUser;

    @JsonProperty("options")
    public List<AmbiguousOptionDto> options = new ArrayList<>();

    public AmbiguousFieldDto() {}
}
