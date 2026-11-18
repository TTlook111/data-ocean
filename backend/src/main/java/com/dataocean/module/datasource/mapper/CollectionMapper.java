package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.PageQuery;
import com.dataocean.module.datasource.entity.po.CollectionExecutionPO;
import com.dataocean.module.datasource.entity.po.CollectionSnapshotPO;
import com.dataocean.module.datasource.entity.po.CollectionTaskPO;
import com.dataocean.module.datasource.mapper.DatasourceMapper.RevisionRow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 保存任务、执行证据和快照状态。调用者按源行、任务、执行记录的顺序协调并发。
 */
@Repository
@RequiredArgsConstructor
public class CollectionMapper {

    /**
     * 平台库连接，源库 JDBC 连接由适配器单独创建。
     */
    private final JdbcTemplate sql;

    /**
     * 采集任务的单表读取和锁定。
     */
    private final CollectionTaskMapper taskRecords;

    /**
     * 访问执行证据及其当前状态。
     */
    private final CollectionExecutionMapper executions;

    /**
     * 访问采集占位所在的来源表。
     */
    private final DatasourceRecordMapper sources;

    /**
     * 已登记的源端运行证据，内含受控的定位信息。
     *
     * @param id 执行记录编号
     * @param locator 加密的会话定位凭据及密钥版本
     */
    public record Execution(String id, byte[] locator) {}

    /**
     * 单表读取采集任务关联的快照。
     */
    private final CollectionSnapshotMapper snapshots;

    /**
     * 接收任务并建立候选快照。确认失败时与同源占位一起回滚。
     */
    public void accept(
            String id,
            String command,
            long source,
            RevisionRow revision,
            Long actor,
            String actorRef,
            String parent,
            Integer scheduleRevision,
            Long cycle,
            LocalDateTime due,
            String timeZone,
            byte[] contract) {
        boolean scheduled = actor == null;
        String mode = scheduled ? "SCHEDULED" : parent == null ? "MANUAL" : "RETRY";
        sql.update(
                """
                INSERT INTO ds_collection_task (id,start_command_id,task_no,source_id,config_revision_id,
                    credential_revision_id,parent_task_id,trigger_kind,actor_kind,actor_id,actor_ref,
                    schedule_revision,schedule_cycle_no,scheduled_for,schedule_time_zone,state,
                    execution_state,stream_epoch,created_at,queue_deadline_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'QUEUED','NOT_STARTED',UUID(),UTC_TIMESTAMP(3),
                    UTC_TIMESTAMP(3)+INTERVAL 5 MINUTE)
                """,
                id,
                command,
                "CJ-" + id,
                source,
                revision.id(),
                revision.credentialId(),
                parent,
                mode,
                scheduled ? "SYSTEM" : "USER",
                actor,
                actorRef,
                scheduleRevision,
                cycle,
                due,
                timeZone);
        sql.update(
                """
                INSERT INTO ds_snapshot (source_id,config_revision_id,task_id,scope_key,comparison_contract_key,
                   adapter_version,comparison_version,status)
                VALUES (?,?,?,?,?,'1','1','CANDIDATE')
                """,
                source,
                revision.id(),
                id,
                revision.scopeKey(),
                contract);
        sources.holdTask(source, id);
    }

    /**
     * 在事务内按执行路径决定是否取得排他行锁。
     */
    public CollectionTaskPO task(String id, boolean lock) {
        return lock ? taskRecords.lock(id) : taskRecords.selectById(id);
    }

    /**
     * 以来源、操作者和命令联合定位原手动任务。
     */
    public CollectionTaskPO byCommand(long source, long actor, String command) {
        return taskRecords.selectOne(Wrappers.<CollectionTaskPO>lambdaQuery()
                .eq(CollectionTaskPO::getSourceId, source)
                .eq(CollectionTaskPO::getActorId, actor)
                .eq(CollectionTaskPO::getStartCommandId, command));
    }

    /**
     * 用同一组过滤条件统计和分页，保留编号的字面包含匹配及实际开始日期的半开区间。
     */
    public Page<CollectionTaskPO> page(
            long source,
            String number,
            String kind,
            String state,
            LocalDateTime from,
            LocalDateTime until,
            PageQuery paging) {
        var filters = Wrappers.<CollectionTaskPO>lambdaQuery()
                .eq(CollectionTaskPO::getSourceId, source)
                .apply("LOCATE({0}, task_no) > 0", number)
                .eq(!kind.isEmpty(), CollectionTaskPO::getTriggerKind, kind)
                .eq(!state.isEmpty(), CollectionTaskPO::getState, state)
                .ge(from != null, CollectionTaskPO::getStartedAt, from)
                .lt(until != null, CollectionTaskPO::getStartedAt, until);

        long total = taskRecords.selectCount(filters);
        var page = paging.<CollectionTaskPO>toLastAvailableMpPage(total);
        return taskRecords.selectPage(
                page, filters.orderByDesc(CollectionTaskPO::getCreatedAt, CollectionTaskPO::getId));
    }

    /**
     * 查找最新任务，用于核对替换凭据时绑定的任务。
     */
    public CollectionTaskPO latest(long source) {
        return taskRecords.selectOne(Wrappers.<CollectionTaskPO>lambdaQuery()
                .eq(CollectionTaskPO::getSourceId, source)
                .orderByDesc(CollectionTaskPO::getCreatedAt, CollectionTaskPO::getId)
                .last("LIMIT 1"));
    }

    /**
     * 选择可从队列领取的任务；真正的领取通过源与任务行锁裁定。
     */
    public List<String> queued() {
        return taskRecords
                .selectList(Wrappers.<CollectionTaskPO>lambdaQuery()
                        .eq(CollectionTaskPO::getState, "QUEUED")
                        .select(CollectionTaskPO::getId)
                        .orderByAsc(CollectionTaskPO::getCreatedAt, CollectionTaskPO::getId)
                        .last("LIMIT 8"))
                .stream()
                .map(CollectionTaskPO::getId)
                .toList();
    }

    /**
     * 选择到期未确认停止的任务或已过期的旧工作者。
     */
    public List<String> recoverable() {
        return taskRecords.recoverable();
    }

    /**
     * 取得排队任务的租约，执行预算从真实开始计算。
     */
    public void claim(CollectionTaskPO task, String owner, int timeout) {
        sql.update(
                """
                UPDATE ds_collection_task SET state='RUNNING',execution_state='RUNNING',phase='CONNECTING',
                    version=version+1,lease_owner=?,lease_until=UTC_TIMESTAMP(3)+INTERVAL 35 SECOND,
                    started_at=UTC_TIMESTAMP(3),execution_deadline_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3))
                WHERE id=? AND state='QUEUED'
                """,
                owner,
                timeout,
                task.getId());
        sql.update("UPDATE ds_snapshot SET read_started_at=UTC_TIMESTAMP(3) WHERE task_id=?", task.getId());
    }

    /**
     * 按当前执行代次延长租约；已经取消或过预算时拒绝发起下一步读取。
     */
    public boolean pulse(CollectionTaskPO task, String phase) {
        return taskRecords.pulse(task.getId(), task.getLeaseOwner(), task.getRunVersion(), phase) == 1;
    }

    /**
     * 在发出实际采集 SQL 前提交本次连接的可核实标识。
     */
    public void register(CollectionTaskPO task, String id, byte[] sourceKey, byte[] envelope) {
        sql.update(
                """
                INSERT INTO ds_collection_execution (id,task_id,run_version,source_instance_key,adapter_version,
                    locator_envelope,state,registered_at)
                VALUES (?,?,?,?,'1',?,'REGISTERED',UTC_TIMESTAMP(3))
                """,
                id,
                task.getId(),
                task.getRunVersion(),
                sourceKey,
                envelope);
    }

    /**
     * 只有已登记的源端会话可以进入读取阶段。
     */
    public void reading(String id) {
        executions.update(Wrappers.<CollectionExecutionPO>lambdaUpdate()
                .eq(CollectionExecutionPO::getId, id)
                .eq(CollectionExecutionPO::getState, "REGISTERED")
                .set(CollectionExecutionPO::getState, "READING"));
    }

    /**
     * 只取指定任务仍需验证关闭的会话证据。
     */
    public List<Execution> outstanding(String task) {
        return executions
                .selectList(Wrappers.<CollectionExecutionPO>lambdaQuery()
                        .eq(CollectionExecutionPO::getTaskId, task)
                        .ne(CollectionExecutionPO::getState, "STOPPED")
                        .select(CollectionExecutionPO::getId, CollectionExecutionPO::getLocatorEnvelope)
                        .orderByAsc(CollectionExecutionPO::getId))
                .stream()
                .map(row -> new Execution(row.getId(), row.getLocatorEnvelope()))
                .toList();
    }

    /**
     * 在获得同实例观察证据后保存停止事实。
     */
    public void stopped(String execution) {
        sql.update(
                """
                UPDATE ds_collection_execution SET state='STOPPED',last_verified_at=UTC_TIMESTAMP(3),
                    stopped_at=UTC_TIMESTAMP(3),stop_proof_kind='SOURCE_SESSION_CLOSED' WHERE id=? AND state<>'STOPPED'
                """,
                execution);
    }

    /**
     * 保留任务及占位，过一段时间再观察源执行是否已停止。
     */
    public void uncertain(CollectionTaskPO task, String code, String message) {
        sql.update(
                """
                UPDATE ds_collection_task SET phase='VERIFYING_STOP',execution_state='UNKNOWN',version=version+1,
                    stop_requested_at=COALESCE(stop_requested_at,UTC_TIMESTAMP(3)),stop_next_check_at=UTC_TIMESTAMP(3)+INTERVAL 3 SECOND,
                    lease_until=UTC_TIMESTAMP(3)+INTERVAL 35 SECOND,failure_code=?,failure_message=? WHERE id=? AND state='RUNNING'
                """,
                code,
                message,
                task.getId());
        sql.update(
                """
                UPDATE ds_collection_execution SET state='UNKNOWN',last_verified_at=UTC_TIMESTAMP(3)
                WHERE task_id=? AND state<>'STOPPED'
                """,
                task.getId());
    }

    /**
     * 废止到期的工作代次，后续只核实此前已登记的连接。
     */
    public void takeOver(CollectionTaskPO task) {
        sql.update(
                """
                UPDATE ds_collection_task SET run_version=run_version+1,stream_epoch=UUID(),lease_owner=NULL
                WHERE id=? AND run_version=? AND state='RUNNING'
                """,
                task.getId(),
                task.getRunVersion());
        uncertain(
                task,
                task.getFailureCode() == null ? "WORKER_INTERRUPTED" : task.getFailureCode(),
                task.getFailureMessage() == null ? "采集执行进程中断，正在核实原连接" : task.getFailureMessage());
    }

    /**
     * 接收取消意图，运行中的任务仍保有源占位。
     */
    public void requestCancel(String task, String reason) {
        sql.update(
                """
                UPDATE ds_collection_task SET cancel_requested_at=UTC_TIMESTAMP(3),stop_requested_at=UTC_TIMESTAMP(3),
                    cancel_reason=?,execution_state='STOP_REQUESTED',version=version+1
                WHERE id=? AND state IN ('QUEUED','RUNNING') AND cancel_requested_at IS NULL
                """,
                reason,
                task);
    }

    /**
     * 枚举完成后同步总数，工作代次变更后的旧执行器不能继续推进。
     */
    public void enumerated(CollectionTaskPO task, int size) {
        taskRecords.update(Wrappers.<CollectionTaskPO>lambdaUpdate()
                .eq(CollectionTaskPO::getId, task.getId())
                .eq(CollectionTaskPO::getRunVersion, task.getRunVersion())
                .set(CollectionTaskPO::isEnumerationComplete, true)
                .set(CollectionTaskPO::getDiscoveredCount, size)
                .set(CollectionTaskPO::getTotalCount, size)
                .set(CollectionTaskPO::getPhase, "READING")
                .setIncrBy(CollectionTaskPO::getVersion, 1));
    }

    /**
     * 为所属工作代次按实际读取结果原子递增进度。
     */
    public void attempted(CollectionTaskPO task, boolean complete) {
        taskRecords.update(Wrappers.<CollectionTaskPO>lambdaUpdate()
                .eq(CollectionTaskPO::getId, task.getId())
                .eq(CollectionTaskPO::getRunVersion, task.getRunVersion())
                .setIncrBy(complete, CollectionTaskPO::getCompleteCount, 1)
                .setIncrBy(!complete, CollectionTaskPO::getFailedCount, 1)
                .setIncrBy(CollectionTaskPO::getVersion, 1));
    }

    /**
     * 归档任务终态，只有已经开始读取的快照才记录读取结束时间。
     */
    public void finishWithoutCatalog(CollectionTaskPO task, String terminal, String code, String message) {
        sql.update(
                """
                UPDATE ds_collection_task SET state=?,execution_state='STOPPED',phase=NULL,version=version+1,
                    ended_at=UTC_TIMESTAMP(3),stop_confirmed_at=UTC_TIMESTAMP(3),failure_code=?,failure_message=?,
                    lease_until=NULL,lease_owner=NULL WHERE id=? AND state IN ('QUEUED','RUNNING')
                """,
                terminal,
                code,
                message,
                task.getId());
        sql.update(
                """
                UPDATE ds_snapshot SET status=?,read_ended_at=IF(read_started_at IS NULL,NULL,UTC_TIMESTAMP(3))
                WHERE task_id=? AND status='CANDIDATE'
                """,
                terminal,
                task.getId());
        sources.releaseTask(task.getSourceId(), task.getId());
    }

    /**
     * 写入成功后的权威任务状态；快照和目录指针由同一事务提交。
     */
    public void finishSuccess(CollectionTaskPO task) {
        sql.update(
                """
                UPDATE ds_collection_task SET state='SUCCEEDED',execution_state='STOPPED',phase=NULL,version=version+1,
                    ended_at=UTC_TIMESTAMP(3),stop_confirmed_at=UTC_TIMESTAMP(3),lease_until=NULL,lease_owner=NULL
                WHERE id=? AND state='RUNNING'
                """,
                task.getId());
    }

    /**
     * 只读取任务接收时已分配的唯一快照。
     */
    public CollectionSnapshotPO snapshot(String task) {
        return Optional.ofNullable(snapshots.selectOne(
                        Wrappers.<CollectionSnapshotPO>lambdaQuery().eq(CollectionSnapshotPO::getTaskId, task)))
                .orElseThrow();
    }

    /**
     * 查询历史快照的配置修订，供目录详情还原当时的采集范围。
     */
    public long snapshotConfiguration(long snapshot) {
        return snapshots
                .selectOne(Wrappers.<CollectionSnapshotPO>lambdaQuery()
                        .select(CollectionSnapshotPO::getConfigRevisionId)
                        .eq(CollectionSnapshotPO::getId, snapshot))
                .getConfigRevisionId();
    }
}
