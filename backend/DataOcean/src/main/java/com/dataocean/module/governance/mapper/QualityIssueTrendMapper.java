package com.dataocean.module.governance.mapper;

import com.dataocean.module.governance.entity.po.IssueTransitionCount;
import com.dataocean.module.governance.entity.po.IssueTrendObservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface QualityIssueTrendMapper {
    /** 以数据库时间划分上海自然日；LEFT JOIN 使丢失覆盖标记可被显式报告，不能误作无历史。 */
    @Select("""
            SELECT s.datasource_id,
                   GREATEST(c.started_at, s.created_at) AS coverage_start,
                   UTC_TIMESTAMP(6) + INTERVAL 8 HOUR AS as_of
            FROM metadata_snapshot s LEFT JOIN quality_issue_trend_coverage c ON c.id = 1
            WHERE s.id = #{snapshotId}
            """)
    IssueTrendObservation observe(@Param("snapshotId") long snapshotId);

    /** 范围和时间条件先走组合索引，再按日期和状态对聚合；原始描述不会进入看板。 */
    @Select("""
            SELECT DATE(occurred_at) AS event_day, previous_status, next_status, COUNT(*) AS quantity
            FROM quality_issue_transition
            WHERE datasource_id = #{source} AND snapshot_id = #{snapshot}
              AND occurred_at >= #{start} AND occurred_at <= #{end}
            GROUP BY DATE(occurred_at), previous_status, next_status
            """)
    List<IssueTransitionCount> transitions(@Param("source") long source, @Param("snapshot") long snapshot,
                                           @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
