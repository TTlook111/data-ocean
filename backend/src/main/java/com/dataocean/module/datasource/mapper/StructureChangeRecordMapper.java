package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.po.StructureChangePO;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

/**
 * 访问 ds_change 的单表处理状态。
 */
public interface StructureChangeRecordMapper extends BaseMapper<StructureChangePO> {

    /**
     * 登记比较发现的变化，用数据库时钟记录发现与初始更新时间。
     */
    @Insert(
            """
            INSERT INTO ds_change (source_id,discovery_task_id,before_snapshot_id,after_snapshot_id,
                before_observation_id,after_observation_id,comparison_object_id,object_id,change_key,discovery_kind,
                change_type,target_kind,attribute_kind,fact_state,stage,before_value,after_value,evidence,discovered_at,updated_at)
            VALUES (#{sourceId},#{discoveryTaskId},#{beforeSnapshotId},#{afterSnapshotId},#{beforeObservationId},
                #{afterObservationId},#{comparisonObjectId},#{objectId},#{changeKey},'COMPARISON',#{changeType},
                #{targetKind},#{attributeKind},#{factState},#{stage},#{beforeValue},#{afterValue},#{evidence},
                UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int createComparison(StructureChangePO row);

    /**
     * 追加人工核实记录，核实时刻由平台库生成。
     */
    @Insert(
            """
            INSERT INTO ds_change_review (source_id,change_id,command_id,actor_kind,actor_id,reviewed_version,decision,
                evidence_text,verified_observation_id,resolved_object_id,resolved_object_revision_id,reviewed_at)
            VALUES (#{source},#{change},#{command},'USER',#{actor},#{version},#{decision},#{evidence},
                #{observation},#{object},#{revision},UTC_TIMESTAMP(3))
            """)
    int appendReview(
            @Param("source") long source,
            @Param("change") long change,
            @Param("command") String command,
            @Param("actor") long actor,
            @Param("version") int version,
            @Param("decision") String decision,
            @Param("evidence") String evidence,
            @Param("observation") Long observation,
            @Param("object") Long object,
            @Param("revision") Long revision);

    /**
     * 仅在重读原身份成功时追加系统恢复证据。
     */
    @Insert(
            """
            INSERT INTO ds_change_review (source_id,change_id,actor_kind,reviewed_version,decision,evidence_text,
                verified_observation_id,reviewed_at)
            VALUES (#{source},#{change},'SYSTEM',#{version},'READ_RESTORED','成功读取已确认的同一来源对象',
                #{observation},UTC_TIMESTAMP(3))
            """)
    int appendRestore(
            @Param("source") long source,
            @Param("change") long change,
            @Param("version") int version,
            @Param("observation") long observation);
}
