package com.dataocean.module.iam.entity.dto;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthorizationChangedDTO {
    private String securityVersion;
    private String changeKind;
    private List<SubjectRefDTO> subjectRefs;
    private List<String> sourceIds;
    private String impactMode;
}
