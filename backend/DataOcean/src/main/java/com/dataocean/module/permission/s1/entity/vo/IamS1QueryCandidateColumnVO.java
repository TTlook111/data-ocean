package com.dataocean.module.permission.s1.entity.vo;

import com.dataocean.module.permission.s1.enums.IamS1ColumnUsage;
import java.util.List;

/** 当前快照中可供 Schema Linking 使用的单字段候选。 */
public record IamS1QueryCandidateColumnVO(
        Long columnMetaId,
        String columnName,
        String columnComment,
        String dataType,
        String governanceStatus,
        String protectionLevel,
        String maskPolicy,
        List<IamS1ColumnUsage> allowedUsages,
        List<IamS1GrantSourceVO> grantSources) {
}
