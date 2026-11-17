package com.dataocean.module.iam.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 影响核对的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImpactPreviewVO {

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
     * 绑定操作者、完整提案、当前版本和到期时间的签名凭据。
     */
    @ToString.Exclude
    private String reviewToken;

    /**
     * 本次实际变化的必要字段差异。
     */
    private List<ChangeVO> changes;

    /**
     * 按当前事实计算的受影响对象及数量。
     */
    private List<ImpactCountVO> affected;

    /**
     * 核对时必须披露的危险后果或后续复核边界。
     */
    private List<String> warnings;
}
