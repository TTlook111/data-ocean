package com.dataocean.module.knowledge.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 持久化逐题运行、原始技术结果和独立人工裁定。
 */
public interface KnowledgeValidationMapper {

    /**
     * 每轮的固定候选、索引、发起人与派发凭据。
     */
    record Run(
            String id,
            long candidateId,
            String buildId,
            long requestedBy,
            long authenticationVersion,
            String operationCode,
            long connectionRevisionId,
            String state,
            String dispatchToken,
            LocalDateTime createdAt,
            Long recoveryPauseId,
            Long recoveryBindingId,
            long taskId,
            long sourceId) {}

    /**
     * 实际冻结的审核题、执行人、技术结果与裁定快照。
     */
    record Question(
            long id,
            long testRevisionId,
            long testId,
            int revision,
            long executorId,
            Long authenticationVersion,
            String accessDigest,
            String content,
            String state,
            Long actualCount,
            String observedChunks,
            String statementText,
            String failureCode,
            String decision,
            String reason,
            Long decidedBy,
            LocalDateTime decidedAt) {}

    /**
     * 通过候选范围读原任务归属，历史范围变更不影响旧运行。
     */
    String RUN =
            """
            SELECT v.*,c.task_id,s.source_id
            FROM kn_release_validation v JOIN kn_release_candidate c ON c.id=v.candidate_id
            JOIN kn_release_scope s ON s.id=c.scope_id
            """;

    /**
     * 记录后台运行，实际调度只处理已提交的事务。
     */
    @Insert(
            """
            INSERT INTO kn_release_validation
            (id,candidate_id,build_id,requested_by,authentication_version,operation_code,connection_revision_id,state,
             recovery_pause_id,recovery_binding_id)
            VALUES(#{id},#{candidate},#{build},#{actor},#{auth},#{operation},#{connection},'QUEUED',#{pause},#{binding})
            """)
    void create(
            @Param("id") String id,
            @Param("candidate") long candidate,
            @Param("build") String build,
            @Param("actor") long actor,
            @Param("auth") long auth,
            @Param("operation") String operation,
            @Param("connection") long connection,
            @Param("pause") Long pause,
            @Param("binding") Long binding);

    /**
     * 原子切换当前运行与任务并发版本。
     */
    @Update(
            """
            UPDATE kn_release_candidate SET current_validation_id=#{run},status='VALIDATING',version=version+1
            WHERE id=#{candidate} AND current_build_id=#{build} AND status=#{previous}
            """)
    int activate(
            @Param("candidate") long candidate,
            @Param("build") String build,
            @Param("previous") String previous,
            @Param("run") String run);

    /**
     * 锁定候选冻结的题目并为本轮记录执行权限快照。
     */
    @Insert(
            """
            INSERT INTO kn_release_validation_result
            (id,run_id,test_revision_id,authentication_version,access_digest,state,failure_code)
            VALUES(#{id},#{run},#{test},#{auth},#{access},
                IF(#{auth} IS NULL,'CONDITIONS_INVALID','PENDING'),
                IF(#{auth} IS NULL,'ACCESS_CHANGED',NULL))
            """)
    void addQuestion(
            @Param("id") long id,
            @Param("run") String run,
            @Param("test") long test,
            @Param("auth") Long auth,
            @Param("access") String access);

    /**
     * 执行人由审核事实确定，不能在验证请求中指定。
     */
    @Select("""
            SELECT executor_id FROM kn_test_review WHERE revision_id=#{revision}
            """)
    long executor(long revision);

    /**
     * 从固定修订回读题目，用写入独立表的裁定覆盖展示状态。
     */
    @Select(
            """
            SELECT q.id,q.test_revision_id,r.test_case_id AS test_id,r.revision,a.executor_id,
                   q.authentication_version,q.access_digest,r.content,q.state,q.actual_count,
                   q.observed_chunks,q.statement_text,q.failure_code,j.decision,j.reason,j.decided_by,j.created_at AS decided_at
            FROM kn_release_validation_result q JOIN kn_test_case_revision r ON r.id=q.test_revision_id
            JOIN kn_test_review a ON a.revision_id=r.id
            LEFT JOIN kn_release_validation_judgment j ON j.result_id=q.id
            WHERE q.run_id=#{run} ORDER BY q.id
            """)
    List<Question> questions(String run);

    /**
     * 原编号回读实际运行。
     */
    @Select(RUN + " WHERE v.id=#{id}")
    Run get(String id);

    /**
     * 查询同一任务的所有旧运行，供详情显示历史入口。
     */
    @Select(RUN + " WHERE c.task_id=#{task} AND v.recovery_pause_id IS NULL ORDER BY v.created_at DESC,v.id DESC")
    List<Run> history(long task);

    /**
     * 隔离任务历史仅回读同一次暂停与正式绑定的恢复运行。
     */
    @Select(
            RUN
                    + """
             WHERE v.recovery_pause_id=#{pause} AND v.recovery_binding_id=#{binding}
             ORDER BY v.created_at DESC,v.id DESC
            """)
    List<Run> recoveryHistory(@Param("pause") long pause, @Param("binding") long binding);

    /**
     * 只领取具备持久派发资格的下一笔运行。
     */
    @Select(
            """
            SELECT id FROM kn_release_validation WHERE state IN ('QUEUED','RUNNING') AND poll_at<=UTC_TIMESTAMP(3)
            ORDER BY poll_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    String next();

    /**
     * 保留短租约，多实例可以继续核实同一编号。
     */
    @Update(
            """
            UPDATE kn_release_validation SET dispatch_token=#{token},poll_at=UTC_TIMESTAMP(3)+INTERVAL 12 SECOND
            WHERE id=#{id}
            """)
    void reserve(@Param("id") String id, @Param("token") String token);

    /**
     * 等待工作者结果时只延后回读原编号。
     */
    @Update(
            """
            UPDATE kn_release_validation SET poll_at=UTC_TIMESTAMP(3)+INTERVAL 1 SECOND,dispatch_token=NULL
            WHERE id=#{id} AND dispatch_token=#{token} AND state IN ('QUEUED','RUNNING')
            """)
    void postpone(@Param("id") String id, @Param("token") String token);

    /**
     * 同一运行只允许取得一次真实外发输入。
     */
    @Update("UPDATE kn_release_validation SET state='RUNNING' WHERE id=#{id} AND state='QUEUED'")
    int claimInput(String id);

    /**
     * 固定本题原始技术结果和实际回读证据。
     */
    @Update(
            """
            UPDATE kn_release_validation_result SET state=#{state},actual_count=#{count},observed_chunks=#{hits},
                statement_text=#{statement},failure_code=#{failure}
            WHERE id=#{id} AND state='PENDING'
            """)
    void result(
            @Param("id") long id,
            @Param("state") String state,
            @Param("count") Long count,
            @Param("hits") String hits,
            @Param("statement") String statement,
            @Param("failure") String failure);

    /**
     * 原始技术结果不修改，人工依据以独立记录保留。
     */
    @Insert(
            """
            INSERT INTO kn_release_validation_judgment(result_id,decision,reason,decided_by)
            VALUES(#{id},#{decision},#{reason},#{actor})
            """)
    void judge(
            @Param("id") long id,
            @Param("decision") String decision,
            @Param("reason") String reason,
            @Param("actor") long actor);

    /**
     * 保存本轮实际汇总结论。
     */
    @Update("UPDATE kn_release_validation SET state=#{state},dispatch_token=NULL WHERE id=#{id}")
    void settle(@Param("id") String id, @Param("state") String state);

    /**
     * 只有仍挂在该候选上的运行才可以推进当前任务阶段。
     */
    @Update(
            """
            UPDATE kn_release_candidate SET status=#{state},version=version+1
            WHERE id=#{candidate} AND current_validation_id=#{run} AND status<>'CANCELED'
            """)
    int advance(@Param("candidate") long candidate, @Param("run") String run, @Param("state") String state);
}
