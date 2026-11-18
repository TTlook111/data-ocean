package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.datasource.entity.po.CollectionSchedulePO;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 更新各数据源的计划游标。
 */
public interface CollectionScheduleMapper extends BaseMapper<CollectionSchedulePO> {

    /**
     * 按数据库时钟读取到期的已启用数据源。
     */
    @Select(
            """
            SELECT p.source_id FROM ds_collection_schedule p JOIN ds_source s ON s.id=p.source_id
            WHERE s.lifecycle='ENABLED' AND (p.next_check_at IS NULL OR p.next_check_at<=UTC_TIMESTAMP(3))
            ORDER BY p.next_check_at,p.source_id LIMIT 32
            """)
    List<Long> due();

    /**
     * 读取来源当前调度状态，缺少计划行时保留原有的零值游标。
     */
    @Select(
            """
            SELECT s.schedule_paused AS paused,p.anchor_at AS anchor,
                COALESCE(p.last_admitted_cycle_no,0) AS last_cycle,COALESCE(p.revision,0) AS revision,
                p.next_check_at AS next_check,s.stale_since,
                GREATEST(COALESCE(s.scope_changed_at,s.first_success_at),COALESCE(o.committed_at,s.first_success_at))
                AS freshness_from
            FROM ds_source s LEFT JOIN ds_collection_schedule p ON p.source_id=s.id
            LEFT JOIN ds_snapshot o ON o.id=s.current_snapshot_id WHERE s.id=#{source}
            """)
    ScheduleRow schedule(@Param("source") long source);

    /**
     * 数据源的调度状态及新鲜度起点。
     *
     * @param paused 是否暂停
     * @param anchor 周期锚点
     * @param lastCycle 已接收的最新周期
     * @param revision 调度修订
     * @param nextCheck 平台记录的下次检查
     * @param staleSince 连续采集失期的起点
     * @param freshnessFrom 当前范围开始计算新鲜度的时间
     */
    record ScheduleRow(
            boolean paused,
            LocalDateTime anchor,
            long lastCycle,
            int revision,
            LocalDateTime nextCheck,
            LocalDateTime staleSince,
            LocalDateTime freshnessFrom) {}

    /**
     * 递增计划修订并重置该数据源的接收游标。
     */
    default int reset(long source) {
        return update(Wrappers.<CollectionSchedulePO>lambdaUpdate()
                .eq(CollectionSchedulePO::getSourceId, source)
                .setIncrBy(CollectionSchedulePO::getRevision, 1)
                .set(CollectionSchedulePO::getLastAdmittedCycleNo, 0L)
                .set(CollectionSchedulePO::getNextCheckAt, null)
                .setSql("anchor_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3)"));
    }
}
