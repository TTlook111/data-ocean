package com.dataocean.module.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.governance.entity.po.QualityCheckBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QualityCheckBatchMapper extends BaseMapper<QualityCheckBatch> {
    /** 门禁可定位最近登记的实际批次。排除未执行墓碑，不推断本次阻断由此批次产生。 */
    @Select("SELECT MAX(id) FROM quality_check_batch WHERE datasource_id=#{source} AND snapshot_id=#{snapshot} AND (operation_id IS NULL OR request_hash IS NOT NULL)")
    Long latestRecordedId(@Param("source") Long source, @Param("snapshot") Long snapshot);
    /** 操作者与请求标识唯一；重复登记保留原指纹和所有者，不能重新领取执行。 */
    @Insert("""
            INSERT INTO quality_check_batch
            (datasource_id,snapshot_id,requested_scope,requested_dimensions_json,requested_table_names_json,
             status,complete,rule_count,executed_rule_count,failed_rule_count,not_applicable_rule_count,
             started_at,created_by,operation_id,request_hash,execution_owner,error_summary,finished_at)
            VALUES (#{datasourceId},#{snapshotId},#{requestedScope},#{requestedDimensionsJson},#{requestedTableNamesJson},
             #{status},0,0,0,0,0,CURRENT_TIMESTAMP,#{createdBy},#{operationId},#{requestHash},#{executionOwner},#{errorSummary},#{finishedAt})
            ON DUPLICATE KEY UPDATE operation_id = operation_id
            """)
    int reserveOperation(QualityCheckBatch batch);

    @Select("SELECT * FROM quality_check_batch WHERE created_by = #{operatorId} AND operation_id = #{operationId} FOR UPDATE")
    QualityCheckBatch selectOperationForUpdate(@Param("operatorId") Long operatorId, @Param("operationId") String operationId);
}
