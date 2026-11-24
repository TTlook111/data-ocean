package com.dataocean.module.knowledge.mapper;

import java.util.List;
import java.util.Set;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 保存知识规划与正文生成的固定输入、单次领取和终态。
 */
public interface KnowledgePlanMapper {

    /**
     * 本轮计划只记录实际发起身份、节点修订及已冻结确认资料。
     *
     * @param id 平台任务编号
     * @param sourceId 当前数据源编号
     * @param requestedBy 实际发起人
     * @param authenticationVersion 发起时的认证版本
     * @param modelRevisionId 节点冻结的模型修订
     * @param snapshot 规划输入快照
     * @param state 当前任务状态
     * @param result 成功建议
     * @param failureCode 可披露失败分类
     * @param operation 规划与正文分别使用自身输入和结果契约
     * @param parentPlanId 正文所采纳的原规划编号
     * @param suggestionIndex 本次采纳的规划建议序号，从零开始
     * @param appliedDocumentId 明确采纳后保存的文档编号
     */
    record Row(
            long id,
            long sourceId,
            long requestedBy,
            long authenticationVersion,
            long modelRevisionId,
            String snapshot,
            String state,
            String result,
            String failureCode,
            String operation,
            Long parentPlanId,
            Integer suggestionIndex,
            Long appliedDocumentId) {}

    /**
     * @param suggestionIndex 原规划中的建议序号
     * @param id 最近可办理的正文任务编号
     */
    record DraftTask(int suggestionIndex, long id) {}

    /**
     * 公共命令受理事务只创建一次任务。
     */
    @Insert(
            """
            INSERT INTO kn_document_plan(id,source_id,requested_by,authentication_version,model_revision_id,snapshot)
            VALUES(#{id},#{source},#{actor},#{authentication},#{revision},#{snapshot})
            """)
    void insert(long id, long source, long actor, long authentication, long revision, String snapshot);

    /**
     * 按稳定编号读取真实规划及其归属。
     */
    @Select("SELECT * FROM kn_document_plan WHERE id=#{id}")
    Row get(long id);

    /**
     * 同一规划的生成和采纳操作先锁定原规划，避免并发采纳重复创建文档。
     */
    @Select("SELECT * FROM kn_document_plan WHERE id=#{id} FOR UPDATE")
    Row lock(long id);

    /**
     * 优先返回已经采纳的文档，否则回看当前建议最近的正文任务。
     */
    @Select("""
            SELECT * FROM kn_document_plan WHERE parent_plan_id=#{plan} AND suggestion_index=#{suggestion}
            ORDER BY (applied_document_id IS NOT NULL) DESC,id DESC LIMIT 1 FOR UPDATE
            """)
    Row latestDraft(long plan, int suggestion);

    /**
     * 回看规划时一次定位全部已提交的正文任务。
     */
    @Select("""
            SELECT suggestion_index, id FROM (
                SELECT suggestion_index, id, ROW_NUMBER() OVER (
                    PARTITION BY suggestion_index ORDER BY (applied_document_id IS NOT NULL) DESC, id DESC
                ) AS task_rank
                FROM kn_document_plan WHERE parent_plan_id=#{plan}
            ) ranked WHERE task_rank=1
            """)
    List<DraftTask> draftTasks(long plan);

    /**
     * 正文任务冻结原规划建议和实际文档修订。
     */
    @Insert("""
            INSERT INTO kn_document_plan(id,source_id,requested_by,authentication_version,model_revision_id,
                snapshot,operation,parent_plan_id,suggestion_index)
            VALUES(#{id},#{source},#{actor},#{authentication},#{revision},#{snapshot},'WRITE',#{plan},#{suggestion})
            """)
    void insertDraft(long id, long source, long actor, long authentication, long revision, String snapshot,
            long plan, int suggestion);

    /**
     * 采纳与实际文档写入在同一事务完成。
     */
    @Update("""
            UPDATE kn_document_plan SET applied_document_id=#{document}
            WHERE id=#{id} AND operation='WRITE' AND state='SUCCEEDED' AND applied_document_id IS NULL
            """)
    int applied(long id, long document);

    /**
     * 仅领取本部署明确允许发给知识模型的来源。
     */
    @Select(
            """
            <script>
            SELECT id FROM kn_document_plan WHERE state='QUEUED' AND source_id IN
            <foreach collection="sources" item="source" open="(" close=")" separator=",">#{source}</foreach>
            ORDER BY id LIMIT 1
            </script>
            """)
    Long next(@Param("sources") Set<Long> sources);

    /**
     * 并行实例只能有一个成功领取，领取后的外发不自动重放。
     */
    @Update(
            """
            UPDATE kn_document_plan SET state='RUNNING',started_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND state='QUEUED'
            """)
    int claim(long id);

    /**
     * 先校验模型回包，再记录规划建议，超期结果不能覆盖终态。
     */
    @Update(
            """
            UPDATE kn_document_plan SET state='SUCCEEDED',result=#{result},ended_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND state='RUNNING'
            """)
    void complete(long id, String result);

    /**
     * 只存受控的诊断分类。
     */
    @Update(
            """
            UPDATE kn_document_plan SET state='FAILED',failure_code=#{code},ended_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND state='RUNNING'
            """)
    void fail(long id, String code);

    /**
     * 进程中断后保留执行未知；排队过期时需用户重新发起。
     */
    @Update(
            """
            UPDATE kn_document_plan SET failure_code=IF(state='RUNNING','MODEL_RESULT_UNKNOWN','QUEUE_EXPIRED'),
                state='FAILED',ended_at=UTC_TIMESTAMP(3)
            WHERE (state='RUNNING' AND started_at<=UTC_TIMESTAMP(3)-INTERVAL 120 SECOND)
               OR (state='QUEUED' AND created_at<=UTC_TIMESTAMP(3)-INTERVAL 300 SECOND)
            """)
    void expire();
}
