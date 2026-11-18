package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.po.CollectionTaskPO;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 按采集任务表的主键及条件访问任务事实。
 */
public interface CollectionTaskMapper extends BaseMapper<CollectionTaskPO> {

    /**
     * 在任务状态转换前取得排他锁，与原有领取和恢复流程共用。
     */
    @Select("SELECT * FROM ds_collection_task WHERE id=#{id} FOR UPDATE")
    CollectionTaskPO lock(String id);

    /**
     * 按平台时钟选择租约已过期或到期重核的执行。
     */
    @Select(
            """
            SELECT id FROM ds_collection_task WHERE state='RUNNING'
            AND (lease_until<UTC_TIMESTAMP(3) OR (execution_state='UNKNOWN' AND stop_next_check_at<=UTC_TIMESTAMP(3)))
            ORDER BY created_at,id LIMIT 8
            """)
    List<String> recoverable();

    /**
     * 仅为当前未取消且仍在预算内的执行代次续租。
     */
    @Update(
            """
            UPDATE ds_collection_task SET phase=#{phase},lease_until=UTC_TIMESTAMP(3)+INTERVAL 35 SECOND,version=version+1
            WHERE id=#{task} AND lease_owner=#{owner} AND run_version=#{runVersion}
            AND state='RUNNING' AND execution_state='RUNNING' AND cancel_requested_at IS NULL
            AND execution_deadline_at>UTC_TIMESTAMP(3)
            """)
    int pulse(
            @Param("task") String task,
            @Param("owner") String owner,
            @Param("runVersion") int runVersion,
            @Param("phase") String phase);
}
