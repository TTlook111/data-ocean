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

    /**
     * 获取当前校验器负责的质量维度。
     *
     * @return 质量维度编码
     */
    String getDimension();

    /**
     * 执行质量校验并返回发现的问题。
     *
     * @param context 本次校验所需的快照元数据和规则上下文
     * @return 质量问题列表
     */
    List<MetadataQualityIssue> check(CheckContext context);

    /**
     * 校验上下文，封装一次校验所需的全部数据
     */
    /**
     * Phase 1 #6: 从 columns 列表中反查列级 issue 的 columnMetaId 并设置。
     * <p>
     * 仅当 issue 有 columnName 时才设置；表级 issue（columnName 为 null）跳过。
     * </p>
     */
    default void resolveAndSetColumnMetaId(MetadataQualityIssue issue, List<DbColumnMeta> columns) {
        if (issue.getColumnName() == null || issue.getTableName() == null) return;
        for (DbColumnMeta col : columns) {
            if (issue.getTableName().equals(col.getTableName())
                && issue.getColumnName().equals(col.getColumnName())) {
                issue.setColumnMetaId(col.getId());
                return;
            }
        }
    }

    record CheckContext(
            Long snapshotId,
            Long datasourceId,
            List<DbTableMeta> tables,
            List<DbColumnMeta> columns,
            List<TableRelation> relations,
            List<MetadataQualityRule> rules
    ) {}
}
