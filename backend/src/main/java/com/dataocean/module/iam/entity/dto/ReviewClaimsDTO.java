package com.dataocean.module.iam.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 影响核对的明确传输结构，字段职责及校验与接口契约一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewClaimsDTO {

    /**
     * 核对凭据绑定的当前操作者标识。
     */
    private String actorId;

    /**
     * 本次业务动作的契约编码，用于选择严格对应的提案类型。
     */
    private String action;

    /**
     * 当前业务目标的稳定标识。
     */
    private String targetId;

    /**
     * 影响核对所依据的目标聚合版本。
     */
    private Integer baseVersion;

    /**
     * 平台当前功能与组织配置的权威安全版本。
     */
    private String securityVersion;

    /**
     * 核对凭据允许使用的最晚 UTC 秒级时间。
     */
    private long expiresAt;

    /**
     * 完整非秘密提案的规范 HMAC 摘要，绑定本次核对内容。
     */
    private String proposalDigest;
}
