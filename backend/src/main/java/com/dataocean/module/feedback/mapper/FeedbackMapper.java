package com.dataocean.module.feedback.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.iam.entity.po.UserPO;
import com.dataocean.module.query.service.QueryProtocol.AnswerRef;
import com.dataocean.module.query.service.QueryProtocol.SourceOption;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 评价事件、摘要和可靠入队的一致性存储。
 */
public interface FeedbackMapper {

    /** 原始评价及恢复待派发记录所需事实。 */
    record Event(
            String id,
            long userId,
            String answerId,
            int answerRevision,
            int sequence,
            String previousEventId,
            String action,
            String reason,
            String commandId,
            LocalDateTime occurredAt) {}

    /** 当前摘要引用最后一条真实评价事件。 */
    record Current(String eventId, String rating, String reason, int version, LocalDateTime updatedAt) {}

    /** 后台事实投影只查基础列。 */
    record Issue(
            long id,
            long sourceId,
            String sourceName,
            String origin,
            String requestId,
            String status,
            Long assigneeId,
            String assigneeName,
            int version,
            String direction,
            Integer resultVersion,
            Integer verificationVersion,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            String summary,
            int referenceCount,
            /**
             * 当前主事项编号，独立事项为空。
             */
            Long mergedInto,
            /**
             * 当前完成轮次，重开后递增。
             */
            int completionRound,
            /**
             * 当前主事项组是否还存在必要的用户补充。
             */
            boolean pendingSupplement) {}

    /** 写操作按确定的下一版本保存操作与必要核查依据。 */
    record Activity(
            long issueId,
            int version,
            long actorId,
            String action,
            Long assigneeId,
            String direction,
            String note,
            String rootCause,
            String conclusion) {}

    /** 操作人和负责人使用当前人员名称，历史的人员编号由事件保留。 */
    record ActivityRow(
            int version,
            String action,
            String actor,
            String assignee,
            String direction,
            String note,
            String rootCause,
            String conclusion,
            LocalDateTime occurredAt,
            Integer taskId,
            Long relatedIssueId,
            int completionRound,
            String closureCode) {}

    /** 搜索仅使用可公开给队列角色的固定摘要，不从原问或反馈原文推断。 */
    String ISSUE_SUMMARY =
            """
            CASE
                WHEN i.origin='USER_DISLIKE' THEN '问数回答收到反馈'
                WHEN q.failure_code='NO_PUBLISHED_KNOWLEDGE' THEN '数据源缺少已发布知识'
                WHEN i.origin='QUERY_KNOWLEDGE_GAP' THEN '现有知识不足以回答原问题'
                WHEN q.execution_state='UNKNOWN' THEN '查询执行状态待核实'
                ELSE '问数执行失败'
            END
            """;

    /**
     * 待补充覆盖办理进度，任务写入继续保留实际办理阶段。
     */
    String WAITING_SUPPLEMENT =
            """
            (i.merged_into IS NULL AND EXISTS(SELECT 1 FROM fb_issue_supplement followup
                JOIN fb_issue origin_issue ON origin_issue.id=followup.issue_id
                WHERE (origin_issue.id=i.id OR origin_issue.merged_into=i.id) AND followup.pending_request IS NOT NULL))
            """;

    /**
     * 主从事项共用基础投影，等待补充独立于实际处理进度。
     */
    String ISSUE_SELECT =
            """
            SELECT i.id,i.source_id,s.name AS source_name,i.origin,i.origin_request_id AS request_id,
                i.status,i.assignee_id,u.display_name AS assignee_name,i.version,i.direction,
                i.result_version,COALESCE(i.verification_version,(
                    SELECT MAX(t.verification_version) FROM fb_issue_task t
                    WHERE t.issue_id=i.id AND t.status='SUBMITTED'
                        AND NOT EXISTS(SELECT 1 FROM fb_issue_task waiting WHERE waiting.issue_id=i.id
                            AND waiting.status='SUBMITTED' AND waiting.verification_version IS NULL)
                )) AS verification_version,i.created_at,i.updated_at,
                GREATEST(1,(SELECT COUNT(*) FROM fb_issue_query_evidence e WHERE e.issue_id=i.id))
                    + COALESCE((SELECT SUM(GREATEST(1,(SELECT COUNT(*) FROM fb_issue_query_evidence linked
                        WHERE linked.issue_id=member.id))) FROM fb_issue member WHERE member.merged_into=i.id),0)
                    AS reference_count,
            """
                    + ISSUE_SUMMARY
                    + """
                AS summary,i.merged_into,i.completion_round,
            """
                    + WAITING_SUPPLEMENT
                    + """
                AS pending_supplement
            FROM fb_issue i JOIN ds_source s ON s.id=i.source_id LEFT JOIN iam_user u ON u.id=i.assignee_id
            JOIN qry_request q ON q.id=i.origin_request_id
            """;

    /** 已有事项使用的来源是队列内的数据源选项。 */
    @Select(
            """
            SELECT CAST(s.id AS CHAR) AS id,s.name FROM ds_source s
            WHERE EXISTS(SELECT 1 FROM fb_issue i WHERE i.source_id=s.id)
            ORDER BY s.name,s.id
            """)
    List<SourceOption> issueSources();

    /** 历史正文可为空，基础查看只需要同版操作元数据。 */
    String ACTIVITY_SELECT =
            """
            SELECT a.issue_version AS version,a.action,u.display_name AS actor,v.display_name AS assignee,
                a.direction,
                CASE WHEN #{includeText} THEN COALESCE(r.summary,vf.actual,a.note) ELSE NULL END AS note,
                CASE WHEN #{includeText} THEN a.root_cause ELSE NULL END AS root_cause,
                CASE WHEN #{includeText} THEN a.conclusion ELSE NULL END AS conclusion,
                a.occurred_at,a.task_id,a.related_issue_id,a.completion_round,
                CASE WHEN a.action='CLOSED' THEN (SELECT MIN(n.template) FROM fb_notice n
                    WHERE n.issue_id=a.issue_id AND n.completion_round=a.completion_round) END AS closure_code
            FROM fb_issue_activity a JOIN iam_user u ON u.id=a.actor_id
            LEFT JOIN iam_user v ON v.id=a.assignee_id
            LEFT JOIN fb_issue_result r ON r.issue_id=a.issue_id AND r.version=a.issue_version
            LEFT JOIN fb_issue_verification vf ON vf.issue_id=a.issue_id AND vf.version=a.issue_version
            WHERE a.issue_id=#{id}
            """;

    /** 只用于诊断等待超出正常派发窗口的点踩。 */
    record Backlog(long pending, LocalDateTime oldest) {}

    /** 同一个数据库时刻用于本次评价的发生与接收。 */
    @Select("SELECT UTC_TIMESTAMP(3)")
    LocalDateTime now();

    /** 由原回答与最后事件取得当前评价。 */
    @Select(
            """
            SELECT c.last_event_id AS event_id,c.rating,e.reason,c.version,c.updated_at
            FROM fb_current_rating c JOIN fb_event e ON e.id=c.last_event_id
            WHERE c.user_id=#{user} AND c.answer_id=#{ref.answerId} AND c.answer_revision=#{ref.answerRevision}
            """)
    Current current(long user, AnswerRef ref);

    /** 以原始点踩事实判断是否已受理。 */
    @Select(
            """
            SELECT EXISTS(SELECT 1 FROM fb_event
            WHERE user_id=#{user} AND answer_id=#{ref.answerId} AND answer_revision=#{ref.answerRevision}
            AND action='DISLIKE')
            """)
    boolean received(long user, AnswerRef ref);

    /** 同一个回答版本始终关联原事项。 */
    @Select(
            """
            SELECT id FROM fb_issue
            WHERE origin='USER_DISLIKE' AND origin_request_id=#{ref.answerId}
            AND origin_answer_revision=#{ref.answerRevision}
            """)
    Long issueId(@Param("ref") AnswerRef ref);

    /** 提交时追加事件，不覆盖已经收到的评价历史。 */
    @Insert(
            """
            INSERT INTO fb_event(id,user_id,answer_id,answer_revision,sequence,previous_event_id,action,reason,
                command_id,occurred_at,received_at)
            VALUES(#{id},#{userId},#{answerId},#{answerRevision},#{sequence},#{previousEventId},#{action},#{reason},
                #{commandId},#{occurredAt},#{occurredAt})
            """)
    void append(Event event);

    /** 首次评价在原回答锁下建立摘要。 */
    @Insert(
            """
            INSERT INTO fb_current_rating(user_id,answer_id,answer_revision,last_event_id,rating,version,updated_at)
            VALUES(#{event.userId},#{event.answerId},#{event.answerRevision},#{event.id},#{rating},
                #{event.sequence},#{event.occurredAt})
            """)
    void insertCurrent(Event event, String rating);

    /** 当前摘要随已提交事件推进，显式保留撤销版本。 */
    @Update(
            """
            UPDATE fb_current_rating SET last_event_id=#{event.id},rating=#{rating},version=#{event.sequence},
                updated_at=#{event.occurredAt}
            WHERE user_id=#{event.userId} AND answer_id=#{event.answerId} AND answer_revision=#{event.answerRevision}
            """)
    void updateCurrent(Event event, String rating);

    /** 消费原始事件，业务主键不从通道载荷重建。 */
    @Select(
            """
            SELECT id,user_id,answer_id,answer_revision,sequence,previous_event_id,action,reason,command_id,occurred_at
            FROM fb_event WHERE id=#{id}
            """)
    Event event(String id);

    /** 创建待分派事项，来源取自原请求。 */
    @Insert(
            """
            INSERT INTO fb_issue(source_id,origin,origin_request_id,origin_answer_revision,status,version)
            VALUES(#{source},'USER_DISLIKE',#{ref.answerId},#{ref.answerRevision},'PENDING_ASSIGNMENT',1)
            """)
    void createIssue(long source, AnswerRef ref);

    /** 关联存在即可用原始事项核对入队回执。 */
    @Select("SELECT issue_id FROM fb_issue_evidence WHERE feedback_event_id=#{event}")
    Long linkedIssue(String event);

    /** 每条实际采用的点踩都保留独立关联。 */
    @Insert("INSERT INTO fb_issue_evidence(feedback_event_id,issue_id) VALUES(#{event},#{issue})")
    void link(String event, long issue);

    /** 已有事项接收新证据时只推进版本与时间。 */
    @Update("UPDATE fb_issue SET version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=#{issue}")
    void touchIssue(long issue);

    /** 查询条件共同用于分页计数，排序由服务端固定。 */
    @Select(
            "<script>" + ISSUE_SELECT
                    + """
            <where>
                <if test="source != null">i.source_id=#{source}</if>
                <if test="issue != null">AND i.id=#{issue}</if>
                <if test="origin != null">AND i.origin=#{origin}</if>
                <if test="status != null">AND (CASE WHEN
            """
                    + WAITING_SUPPLEMENT
                    + """
                    THEN 'PENDING_SUPPLEMENT' ELSE i.status END)=#{status}
                </if>
                <if test="assignee != null">AND u.display_name LIKE #{assignee} ESCAPE '!'</if>
                <if test="keyword != null">
                    AND (CONCAT('#',i.id) LIKE #{keyword} ESCAPE '!' OR (
            """
                    + ISSUE_SUMMARY
                    + """
                    ) LIKE #{keyword} ESCAPE '!')
                </if>
                <if test="scope == 'OWNED'">
                    AND i.status IN ('IN_PROGRESS','PENDING_VERIFICATION') AND (i.assignee_id=#{actor}
                        OR EXISTS(SELECT 1 FROM fb_issue_task t WHERE t.issue_id=i.id AND t.assignee_id=#{actor}
                            AND t.status IN ('IN_PROGRESS','SUBMITTED')))
                </if>
                <if test="scope == 'CLOSED'">AND i.status IN ('RESOLVED','CLOSED_OTHER','MERGED')</if>
                <if test="scope == 'MERGE'">AND i.status IN ('PENDING_ASSIGNMENT','IN_PROGRESS','PENDING_VERIFICATION')</if>
                <if test="scope == 'VERIFY_CANDIDATES'">
                    AND i.status='PENDING_VERIFICATION' AND NOT
            """
                    + WAITING_SUPPLEMENT
                    + """
                    AND (i.result_version IS NOT NULL OR EXISTS(SELECT 1 FROM fb_issue_task t
                        WHERE t.issue_id=i.id AND t.status='SUBMITTED'))
                    <if test="!canClose">
                        AND i.source_id IN
                        <foreach collection="verifySources" item="id" open="(" separator="," close=")">#{id}</foreach>
                    </if>
                </if>
                <if test="scope == 'MINE'">
                    AND NOT
            """
                    + WAITING_SUPPLEMENT
                    + """
                    AND ((#{canAssign} AND i.status='PENDING_ASSIGNMENT')
                        OR (#{canReview} AND i.assignee_id=#{actor} AND i.status='IN_PROGRESS'
                            AND NOT EXISTS(SELECT 1 FROM fb_issue_task t WHERE t.issue_id=i.id
                                AND t.assignee_id=#{actor} AND t.waiting_external=TRUE)
                            AND (NOT EXISTS(SELECT 1 FROM fb_issue_task t WHERE t.issue_id=i.id)
                                OR NOT EXISTS(SELECT 1 FROM fb_issue_activity a
                                    WHERE a.issue_id=i.id AND a.action='REVIEWED' AND a.actor_id=#{actor}
                                    AND a.issue_version &gt; COALESCE((SELECT MAX(b.issue_version)
                                        FROM fb_issue_activity b WHERE b.issue_id=i.id
                                            AND b.action IN ('ASSIGNED','REOPENED','UNMERGED')),0))))
                        OR (#{canReview} AND i.status='IN_PROGRESS' AND EXISTS(SELECT 1 FROM fb_issue_task t
                            WHERE t.issue_id=i.id AND t.assignee_id=#{actor} AND t.status='IN_PROGRESS'
                                AND t.waiting_external=FALSE))
                        <if test="verificationTodoIds.size() &gt; 0">
                            OR i.id IN
                            <foreach collection="verificationTodoIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                        </if>
                        OR (#{canClose} AND i.status='IN_PROGRESS' AND NOT EXISTS(SELECT 1 FROM fb_issue_task t
                                WHERE t.issue_id=i.id AND t.status IN ('IN_PROGRESS','SUBMITTED'))
                                AND EXISTS(SELECT 1 FROM fb_issue_activity a
                                WHERE a.issue_id=i.id AND a.actor_id=i.assignee_id AND a.action='REVIEWED'
                                AND a.issue_version &gt; COALESCE((SELECT MAX(b.issue_version) FROM fb_issue_activity b
                                    WHERE b.issue_id=i.id AND b.action IN ('ASSIGNED','REOPENED','UNMERGED')),0))))
                </if>
            </where>
            ORDER BY i.updated_at DESC,i.id DESC
            </script>
            """)
    IPage<Issue> list(
            Page<Issue> page,
            Long source,
            Long issue,
            String scope,
            String origin,
            String keyword,
            String status,
            String assignee,
            long actor,
            boolean canAssign,
            boolean canReview,
            List<Long> verifySources,
            List<Long> verificationTodoIds,
            boolean canClose);

    /** 独立打开事项使用同一基础投影。 */
    @Select(ISSUE_SELECT + " WHERE i.id=#{id}")
    Issue issue(long id);

    /**
     * 业务写锁只锁当前事项，主从并发操作由服务层按编号顺序取锁。
     */
    @Select(
            """
            SELECT i.id,i.source_id,NULL AS source_name,i.origin,i.origin_request_id AS request_id,
                i.status,i.assignee_id,NULL AS assignee_name,i.version,i.direction,i.result_version,i.verification_version,
                i.created_at,i.updated_at,NULL AS summary,0 AS reference_count,i.merged_into,i.completion_round,
            """
                    + WAITING_SUPPLEMENT
                    + """
                AS pending_supplement
            FROM fb_issue i WHERE i.id=#{id} FOR UPDATE OF i
            """)
    Issue lockIssue(long id);

    /** 分派和转派原子推进版本，仅更新本动作确实改变的责任字段。 */
    @Update(
            """
            UPDATE fb_issue SET assignee_id=#{assignee},direction=#{direction},status=#{status},
                result_version=NULL,verification_version=NULL,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND version=#{version} AND status IN ('PENDING_ASSIGNMENT','IN_PROGRESS','PENDING_VERIFICATION')
            """)
    int assign(long id, int version, long assignee, String direction, String status);

    /** 当前负责人核查不改变事项主状态，但推进聚合版本。 */
    @Update(
            """
            UPDATE fb_issue SET version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND version=#{version} AND assignee_id=#{actor} AND status='IN_PROGRESS'
            """)
    int review(long id, int version, long actor);

    /** 审计和责任变更同事务追加业务历史。 */
    @Insert(
            """
            INSERT INTO fb_issue_activity(issue_id,issue_version,actor_id,action,assignee_id,direction,note,root_cause,
                conclusion,completion_round)
            SELECT i.id,#{version},#{actorId},#{action},#{assigneeId},#{direction},#{note},#{rootCause},#{conclusion},
                i.completion_round FROM fb_issue i WHERE i.id=#{issueId}
            """)
    void appendActivity(Activity event);

    /** 回看最近处理过程，保留已发生的操作顺序。 */
    @Select(ACTIVITY_SELECT + " ORDER BY a.issue_version DESC LIMIT 20")
    List<ActivityRow> activities(long id, boolean includeText);

    /** 核查摘要独立定位最新核查记录，转派后仍保留。 */
    @Select(ACTIVITY_SELECT + " AND a.action='REVIEWED' ORDER BY a.issue_version DESC LIMIT 1")
    ActivityRow latestReview(long id, boolean includeText);

    /** 处理结果须建立在当前负责人最近一次分派后的真实核查上。 */
    @Select(
            """
            SELECT EXISTS (SELECT 1 FROM fb_issue_activity a WHERE a.issue_id=#{id} AND a.actor_id=#{actor}
                AND a.action='REVIEWED' AND a.issue_version > COALESCE((SELECT MAX(b.issue_version)
                  FROM fb_issue_activity b WHERE b.issue_id=#{id} AND b.action IN ('ASSIGNED','REOPENED','UNMERGED')),0))
            """)
    boolean hasCurrentReview(long id, long actor);

    /** 沿用真实关联事件读取最近反馈，原因清理后的事件仍可定位。 */
    @Select(
            """
            SELECT f.id,f.user_id,f.answer_id,f.answer_revision,f.sequence,f.previous_event_id,
                f.action,f.reason,f.command_id,f.occurred_at
            FROM fb_issue_evidence e JOIN fb_event f ON f.id=e.feedback_event_id
            WHERE e.issue_id=#{id} ORDER BY f.occurred_at DESC,f.id DESC LIMIT 20
            """)
    List<Event> reports(long id);

    /** 候选按当前有效角色筛选，分页发生在权限过滤之后。 */
    @Select(
            """
            <script>
            SELECT u.id,u.username,u.display_name,u.version FROM iam_user u
            WHERE u.lifecycle='ENABLED' AND u.must_change_password=0
                AND 2=(SELECT COUNT(DISTINCT rp.permission_code) FROM iam_user_role ur
                    JOIN iam_role r ON r.id=ur.role_id AND r.lifecycle='ENABLED'
                    JOIN iam_role_permission rp ON rp.role_id=r.id
                    JOIN iam_permission p ON p.code=rp.permission_code AND p.enabled=1
                    WHERE ur.user_id=u.id AND ur.ended_at IS NULL
                      AND rp.permission_code IN ('feedback.view','feedback.process'))
                <if test="pattern != null">
                    AND (LOWER(u.display_name) LIKE #{pattern} ESCAPE '!'
                        OR LOWER(u.username) LIKE #{pattern} ESCAPE '!')
                </if>
            ORDER BY u.display_name,u.id
            </script>
            """)
    IPage<UserPO> candidates(Page<UserPO> page, String pattern);

    /** 按积压点踩补足仅靠租约重投无法覆盖的队列缺口。 */
    @Select(
            """
            SELECT f.id FROM fb_event f
            LEFT JOIN sys_outbox o ON o.event_id=f.id
            LEFT JOIN sys_event_receipt r ON r.event_id=f.id AND r.consumer_code='FEEDBACK_ISSUE_INTAKE'
            LEFT JOIN fb_issue_evidence e ON e.feedback_event_id=f.id
            WHERE f.action='DISLIKE' AND f.received_at<=UTC_TIMESTAMP(3)-INTERVAL 10 SECOND
            AND ((o.event_id IS NULL AND e.feedback_event_id IS NULL)
                OR (o.delivery_state='DELIVERED' AND (e.feedback_event_id IS NULL OR r.event_id IS NULL)))
            ORDER BY f.received_at,f.id LIMIT 50
            """)
    List<String> reconcileCandidates();

    /** 监测已受理但未建立事项关联的积压。 */
    @Select(
            """
            SELECT COUNT(*) AS pending,MIN(f.received_at) AS oldest FROM fb_event f
            LEFT JOIN fb_issue_evidence e ON e.feedback_event_id=f.id
            WHERE f.action='DISLIKE' AND f.received_at<=UTC_TIMESTAMP(3)-INTERVAL 10 SECOND
            AND e.feedback_event_id IS NULL
            """)
    Backlog backlog();

    /** 已批准的保留策略单独处置原因正文，保留评价事件身份。 */
    @Update(
            """
            UPDATE fb_event SET reason=NULL,reason_purged_at=UTC_TIMESTAMP(3)
            WHERE action='DISLIKE' AND received_at<#{cutoff} AND reason IS NOT NULL
                AND EXISTS(SELECT 1 FROM fb_issue_evidence WHERE feedback_event_id=fb_event.id)
            ORDER BY received_at,id LIMIT 200
            """)
    int purgeReasons(LocalDateTime cutoff);
}
