package com.dataocean.module.permission.s1.entity.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * S1 数据授权视图。用于“数据授权”工作区的列表与详情，页面不暴露 scope/effect/policy/revision 等技术词。
 *
 * @param summary 中文摘要，例如“允许销售部及下级部门查询销售库的订单表，限区域为华东的记录”
 */
public record IamS1DataGrantVO(Long id,
                               Long datasourceId,
                               String datasourceName,
                               String subjectType,
                               String subjectTypeName,
                               Long subjectId,
                               String subjectName,
                               String departmentScope,
                               String departmentScopeName,
                               String resourceScope,
                               String resourceScopeName,
                               Long metadataSnapshotId,
                               String tableName,
                               String effect,
                               String effectName,
                               String grantSource,
                               Long sourceReferenceId,
                               LocalDateTime validFrom,
                               LocalDateTime validUntil,
                               String status,
                               Long revisionNo,
                               List<String> columns,
                               String rowMatchType,
                               List<IamS1RowConditionSummaryVO> rowConditions,
                               String summary) {
}
