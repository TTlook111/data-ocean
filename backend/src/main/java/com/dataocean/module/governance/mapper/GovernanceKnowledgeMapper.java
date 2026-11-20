package com.dataocean.module.governance.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 只定位可能可引用的治理目标，业务资格由治理服务继续复核。
 */
public interface GovernanceKnowledgeMapper {

    /**
     * 当前确认记录的稳定目标。
     */
    record Ref(long objectId, String kind, long targetId) {}

    /**
     * 按数据源和类型读取稳定身份，不把草稿或候选身份作为确认结果。
     */
    @Select(
            """
            SELECT f.object_id, f.kind, f.target_id FROM (
                SELECT i.object_id, i.kind, i.target_id
                FROM gov_item i
                WHERE i.source_id=#{source} AND i.head_revision_id=i.last_confirmed_revision_id
                UNION ALL
                SELECT c.object_id, c.kind, c.id AS target_id
                FROM gov_discovery_candidate c JOIN gov_discovery_revision r
                  ON r.candidate_id=c.id AND r.version=c.version AND r.state='CONFIRMED'
                WHERE c.source_id=#{source}
            ) f
            WHERE (#{kind} IS NULL OR f.kind=#{kind})
              AND (#{object} IS NULL OR f.object_id=#{object})
            ORDER BY f.kind, f.object_id, f.target_id
            """)
    Page<Ref> confirmed(
            Page<Ref> page, @Param("source") long source, @Param("kind") String kind, @Param("object") Long object);
}
