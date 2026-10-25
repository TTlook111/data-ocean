package com.dataocean.module.fieldsemantic.entity.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.*;

@Data
public class BusinessKnowledgeDraftDTO {
    @Positive private Long expectedRevisionId;
    @Min(1) private Integer expectedVersion;
    @Pattern(regexp="[0-9a-f]{64}") private String confirmedSourceHash;
    @NotBlank @Size(max=24) private String kind;
    @NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{1,100}") private String knowledgeKey;
    @NotBlank @Size(max=200) private String title;
    @NotNull private Map<String, String> body;
    @NotEmpty @Size(max=2000) private List<String> dependencies;
    @Size(max=8000) private String evidence;
    @Size(max=2000) private String evidenceReference;
    private boolean evidenceConfirmed;
    @NotNull @Size(max=50) private List<String> unresolvedItems = new ArrayList<>();
}
