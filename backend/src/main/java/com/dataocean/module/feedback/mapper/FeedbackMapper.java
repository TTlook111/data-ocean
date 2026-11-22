package com.dataocean.module.feedback.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.query.service.QueryProtocol.AnswerRef;

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
            String origin,
            String status,
            Long assigneeId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

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
            WHERE origin='USER_DISLIKE' AND origin_answer_id=#{ref.answerId}
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
            INSERT INTO fb_issue(source_id,origin,origin_answer_id,origin_answer_revision,status,version)
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
            """
            <script>
            SELECT id,source_id,origin,status,assignee_id,created_at,updated_at FROM fb_issue
            <where>
                <if test="source != null">source_id=#{source}</if>
                <if test="issue != null">AND id=#{issue}</if>
            </where>
            ORDER BY updated_at DESC,id DESC
            </script>
            """)
    IPage<Issue> list(Page<Issue> page, Long source, Long issue);

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
