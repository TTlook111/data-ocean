package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class ImpactPreviewVO {
    private String action;
    private String targetId;
    private Integer baseVersion;
    private String securityVersion;
    private String reviewToken;
    private List<ChangeVO> changes;
    private List<ImpactCountVO> affected;
    private List<String> warnings;
}
