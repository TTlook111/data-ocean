package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.datasource.entity.po.CollectionSnapshotPO;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 查询采集快照及其固定的配置修订。
 */
public interface CollectionSnapshotMapper extends BaseMapper<CollectionSnapshotPO> {

    /**
     * 只在范围与比较协议一致的同源成功快照中选择最近一条。
     */
    @Select(
            """
            SELECT previous.id FROM ds_snapshot previous JOIN ds_snapshot newer
              ON newer.scope_key=previous.scope_key AND newer.comparison_contract_key=previous.comparison_contract_key
            WHERE previous.source_id=#{source} AND newer.id=#{candidate} AND previous.status='SUCCEEDED'
            ORDER BY previous.committed_at DESC,previous.id DESC LIMIT 1
            """)
    Long comparable(@Param("source") long source, @Param("candidate") long candidate);

    /**
     * 在任务被领取时记录源读取的开始时间。
     */
    default int startRead(String task) {
        return update(Wrappers.<CollectionSnapshotPO>lambdaUpdate()
                .eq(CollectionSnapshotPO::getTaskId, task)
                .setSql("read_started_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 归档任务的候选快照，只有曾开始读取才记录结束时间。
     */
    default int abandon(String task, String terminal) {
        return update(Wrappers.<CollectionSnapshotPO>lambdaUpdate()
                .eq(CollectionSnapshotPO::getTaskId, task)
                .eq(CollectionSnapshotPO::getStatus, "CANDIDATE")
                .set(CollectionSnapshotPO::getStatus, terminal)
                .setSql("read_ended_at=IF(read_started_at IS NULL,NULL,UTC_TIMESTAMP(3))"));
    }

    /**
     * 仅发布所属数据源的候选快照，并保存两类前序快照和提交结果。
     */
    default int publish(long source, long snapshot, Long previous, Long comparison, String analysis, long changes) {
        return update(Wrappers.<CollectionSnapshotPO>lambdaUpdate()
                .eq(CollectionSnapshotPO::getId, snapshot)
                .eq(CollectionSnapshotPO::getSourceId, source)
                .eq(CollectionSnapshotPO::getStatus, "CANDIDATE")
                .set(CollectionSnapshotPO::getStatus, "SUCCEEDED")
                .set(CollectionSnapshotPO::getAnalysis, analysis)
                .set(CollectionSnapshotPO::getNewChangeCount, changes)
                .set(CollectionSnapshotPO::getPreviousSuccessSnapshotId, previous)
                .set(CollectionSnapshotPO::getComparisonSnapshotId, comparison)
                .setSql("read_ended_at=UTC_TIMESTAMP(3),committed_at=UTC_TIMESTAMP(3)"));
    }
}
