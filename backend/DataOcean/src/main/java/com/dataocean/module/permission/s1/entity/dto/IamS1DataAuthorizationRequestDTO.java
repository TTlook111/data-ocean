package com.dataocean.module.permission.s1.entity.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** S1 数据授权计算请求。仅描述本次查询引用的资源，不接受前端身份参数。 */
@Getter
@Setter
@NoArgsConstructor
public class IamS1DataAuthorizationRequestDTO {
    private String protocolVersion;
    private Long userId;
    private Long datasourceId;
    private Long activeMetadataSnapshotId;
    private LocalDateTime calculatedAt;
    private List<IamS1TableRequestDTO> tables = new ArrayList<>();
}
