package com.dataocean.module.governance.checker;

import com.dataocean.module.governance.entity.MetadataQualityIssue;
import com.dataocean.module.governance.entity.MetadataQualityRule;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.entity.TableRelation;

import java.util.List;

/**
 * 质量校验器接口，每个维度实现一个 Checker
 */
public interface QualityChecker {

    String getDimension();

    List<MetadataQualityIssue> check(CheckContext context);

    /**
     * 校验上下文，封装一次校验所需的全部数据
     */
    record CheckContext(
            Long snapshotId,
            Long datasourceId,
            List<DbTableMeta> tables,
            List<DbColumnMeta> columns,
            List<TableRelation> relations,
            List<MetadataQualityRule> rules
    ) {}
}
