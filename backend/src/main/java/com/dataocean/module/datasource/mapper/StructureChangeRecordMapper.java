package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.po.StructureChangePO;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 访问 ds_change 的单表处理状态。
 */
public interface StructureChangeRecordMapper extends BaseMapper<StructureChangePO> {

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
}
