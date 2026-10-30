package com.dataocean.module.permission.s1.entity.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** S1 数据授权写入请求；记录条件只能使用结构化谓词。 */
@Getter
@Setter
@NoArgsConstructor
public class IamS1DataGrantSaveDTO {
    private String protocolVersion;
    private String subjectType;
    private Long subjectId;
    private String departmentScope;
    private Long datasourceId;
    private String resourceScope;
    private Long metadataSnapshotId;
    private String tableName;
    private String effect;
    private String grantSource;
    private Long sourceReferenceId;
    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
    private String status;
    private String rowMatchType;
    private List<IamS1DataGrantColumnDTO> columns = new ArrayList<>();
    private List<IamS1RowConditionDTO> rowConditions = new ArrayList<>();
    private String reason;
}
