package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.datasource.entity.po.CollectionTaskPO;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 按采集任务表的主键及条件访问任务事实。
 */
public interface CollectionTaskMapper extends BaseMapper<CollectionTaskPO> {

    /**
     * 接收任务时固定来源、发起依据和排队期限，接收时刻由同一条语句生成。
     */
    @Insert(
            """
            INSERT INTO ds_collection_task (id,start_command_id,task_no,source_id,config_revision_id,
                credential_revision_id,parent_task_id,trigger_kind,actor_kind,actor_id,actor_ref,
                schedule_revision,schedule_cycle_no,scheduled_for,schedule_time_zone,state,
                execution_state,stream_epoch,created_at,queue_deadline_at)
            VALUES (#{row.id},#{row.startCommandId},#{row.taskNo},#{row.sourceId},#{row.configRevisionId},
                #{credential},#{row.parentTaskId},#{row.triggerKind},#{actorKind},#{row.actorId},#{row.actorRef},
                #{scheduleRevision},#{cycle},#{due},#{timeZone},'QUEUED','NOT_STARTED',UUID(),UTC_TIMESTAMP(3),
                UTC_TIMESTAMP(3)+INTERVAL 5 MINUTE)
            """)
    int accept(
            @Param("row") CollectionTaskPO row,
            @Param("credential") Long credential,
            @Param("actorKind") String actorKind,
            @Param("scheduleRevision") Integer scheduleRevision,
            @Param("cycle") Long cycle,
            @Param("due") LocalDateTime due,
            @Param("timeZone") String timeZone);

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
     * 仅领取仍处于排队状态的任务，并同时计算租约及执行期限。
     */
    @Update(
            """
            UPDATE ds_collection_task SET state='RUNNING',execution_state='RUNNING',phase='CONNECTING',
                version=version+1,lease_owner=#{owner},lease_until=UTC_TIMESTAMP(3)+INTERVAL 35 SECOND,
                started_at=UTC_TIMESTAMP(3),execution_deadline_at=TIMESTAMPADD(SECOND,#{timeout},UTC_TIMESTAMP(3))
            WHERE id=#{task} AND state='QUEUED'
            """)
    int claim(@Param("task") String task, @Param("owner") String owner, @Param("timeout") int timeout);

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

    /**
     * 记录等待核实停止的状态，同时延后重核与租约时点。
     */
    @Update(
            """
            UPDATE ds_collection_task SET phase='VERIFYING_STOP',execution_state='UNKNOWN',version=version+1,
                stop_requested_at=COALESCE(stop_requested_at,UTC_TIMESTAMP(3)),
                stop_next_check_at=UTC_TIMESTAMP(3)+INTERVAL 3 SECOND,
                lease_until=UTC_TIMESTAMP(3)+INTERVAL 35 SECOND,
                failure_code=#{code},failure_message=#{message} WHERE id=#{task} AND state='RUNNING'
            """)
    int uncertain(@Param("task") String task, @Param("code") String code, @Param("message") String message);

    /**
     * 仅废止调用者已核对的旧工作代次。
     */
    default int takeOver(String task, int runVersion) {
        return update(Wrappers.<CollectionTaskPO>lambdaUpdate()
                .eq(CollectionTaskPO::getId, task)
                .eq(CollectionTaskPO::getRunVersion, runVersion)
                .eq(CollectionTaskPO::getState, "RUNNING")
                .setIncrBy(CollectionTaskPO::getRunVersion, 1)
                .set(CollectionTaskPO::getLeaseOwner, null)
                .setSql("stream_epoch=UUID()"));
    }

    /**
     * 首次接收取消时同时记录取消、停止请求及其状态版本。
     */
    default int requestCancel(String task, String reason) {
        return update(Wrappers.<CollectionTaskPO>lambdaUpdate()
                .eq(CollectionTaskPO::getId, task)
                .in(CollectionTaskPO::getState, "QUEUED", "RUNNING")
                .isNull(CollectionTaskPO::getCancelRequestedAt)
                .set(CollectionTaskPO::getCancelReason, reason)
                .set(CollectionTaskPO::getExecutionState, "STOP_REQUESTED")
                .setIncrBy(CollectionTaskPO::getVersion, 1)
                .setSql("cancel_requested_at=UTC_TIMESTAMP(3),stop_requested_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 确认指定未结束任务的终态，清理租约并记录停止与结束时刻。
     */
    @Update(
            """
            UPDATE ds_collection_task SET state=#{terminal},execution_state='STOPPED',phase=NULL,version=version+1,
                ended_at=UTC_TIMESTAMP(3),stop_confirmed_at=UTC_TIMESTAMP(3),failure_code=#{code},failure_message=#{message},
                lease_until=NULL,lease_owner=NULL WHERE id=#{task} AND state IN ('QUEUED','RUNNING')
            """)
    int finishWithoutCatalog(
            @Param("task") String task,
            @Param("terminal") String terminal,
            @Param("code") String code,
            @Param("message") String message);

    /**
     * 只将运行中的任务记为成功，释放原工作者租约。
     */
    default int finishSuccess(String task) {
        return update(Wrappers.<CollectionTaskPO>lambdaUpdate()
                .eq(CollectionTaskPO::getId, task)
                .eq(CollectionTaskPO::getState, "RUNNING")
                .set(CollectionTaskPO::getState, "SUCCEEDED")
                .set(CollectionTaskPO::getExecutionState, "STOPPED")
                .set(CollectionTaskPO::getPhase, null)
                .set(CollectionTaskPO::getLeaseUntil, null)
                .set(CollectionTaskPO::getLeaseOwner, null)
                .setIncrBy(CollectionTaskPO::getVersion, 1)
                .setSql("ended_at=UTC_TIMESTAMP(3),stop_confirmed_at=UTC_TIMESTAMP(3)"));
    }
}
