package com.dataocean.module.permission.s1.entity.vo;

import java.util.List;

/** 仅当至少有一个当前可见字段时返回的表候选。 */
public record IamS1QueryCandidateTableVO(
        String tableName,
        String tableComment,
        String governanceStatus,
        List<IamS1QueryCandidateColumnVO> columns) {
}
