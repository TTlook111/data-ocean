package com.dataocean.module.fieldsemantic.entity.dto;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class GovernanceFeedbackDTO {
    @NotBlank @Pattern(regexp="LIKE|DISLIKE") private String feedbackType;
    @Pattern(regexp="UNKNOWN|FIELD_MEANING|FIELD_USAGE|RELATION|METRIC|SQL|PRESENTATION") private String reasonCode="UNKNOWN";
    @Size(max=4000) private String comment;
    private Long columnMetaId;
}
