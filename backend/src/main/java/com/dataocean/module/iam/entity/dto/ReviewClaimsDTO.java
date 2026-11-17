package com.dataocean.module.iam.entity.dto;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewClaimsDTO {
    private String actorId;
    private String action;
    private String targetId;
    private Integer baseVersion;
    private String securityVersion;
    private long expiresAt;
    private String proposalDigest;
}
