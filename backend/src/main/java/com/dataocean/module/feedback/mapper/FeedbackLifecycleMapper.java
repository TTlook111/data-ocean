package com.dataocean.module.feedback.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 事项关联、原问题补充与结案轮次的持久事实。
 */
public interface FeedbackLifecycleMapper {

    /**
     * 同一原事项下的原问和真实提问人。
     */
    record Origin(long issueId, String requestId, long recipientId, String recipientName, String origin) {}

    /**
     * 原事项中的一次补充记录。实际回复及人工处置分别留存。
     */
    record Supplement(
            String id,
            long issueId,
            String requestId,
            String prompt,
            String answer,
            LocalDateTime requestedAt,
            LocalDateTime answeredAt,
            String disposition,
            String dispositionReason,
            LocalDateTime disposedAt) {
        /**
         * 收到实际回复或人工处置后，该请求不再等待原提问人。
         */
        public boolean pending() {
            return answeredAt == null && disposedAt == null;
        }
    }

    /**
     * 主事项和直接成员各自的原问，自动缺口可能汇集同一提问人的多条问数。
     */
    @Select(
            """
            WITH members AS (
                SELECT id,origin_request_id FROM fb_issue WHERE id=#{issue} OR merged_into=#{issue}
            ), references_to_query AS (
                SELECT id AS issue_id,origin_request_id AS request_id FROM members
                UNION SELECT e.issue_id,e.request_id FROM fb_issue_query_evidence e JOIN members m ON m.id=e.issue_id
            )
            SELECT r.issue_id,r.request_id,q.user_id AS recipient_id,u.display_name AS recipient_name,i.origin
            FROM references_to_query r JOIN qry_request q ON q.id=r.request_id
            JOIN iam_user u ON u.id=q.user_id JOIN fb_issue i ON i.id=r.issue_id
            ORDER BY r.issue_id,r.request_id
            """)
    List<Origin> origins(long issue);

    /**
     * 本人端按服务端原问关系定位原事项，不接收客户端传入的事项或收件人。
     */
    @Select(
            """
            SELECT i.id AS issue_id,q.id AS request_id,q.user_id AS recipient_id,u.display_name AS recipient_name,i.origin
            FROM qry_request q JOIN iam_user u ON u.id=q.user_id
            JOIN fb_issue i ON i.id=COALESCE(
                (SELECT e.issue_id FROM fb_issue_query_evidence e WHERE e.request_id=q.id),
                (SELECT f.id FROM fb_issue f WHERE f.origin_request_id=q.id ORDER BY f.id DESC LIMIT 1))
            WHERE q.id=#{request} AND q.user_id=#{actor}
            """)
    Origin ownOrigin(String request, long actor);

    /**
     * 只读回看当前关联事项，不折叠原始编号。
     */
    @Select("SELECT id FROM fb_issue WHERE merged_into=#{issue} ORDER BY id")
    List<Long> members(long issue);

    /**
     * 在非空主事项锁下验证当前组内是否有仍被质疑的发布绑定。
     */
    @Select(
            """
            SELECT EXISTS(
                SELECT 1 FROM fb_issue i JOIN qry_request q ON q.id=i.origin_request_id
                WHERE (i.id=#{issue} OR i.merged_into=#{issue}) AND q.binding_id=#{binding}
                UNION ALL
                SELECT 1 FROM fb_issue i JOIN fb_issue_query_evidence e ON e.issue_id=i.id
                JOIN qry_request q ON q.id=e.request_id
                WHERE (i.id=#{issue} OR i.merged_into=#{issue}) AND q.binding_id=#{binding}
            )
            """)
    boolean usedBinding(long issue, long binding);

    /**
     * 选择原问题时返回自己的补充记录，待回复优先。
     */
    @Select(
            """
            SELECT id,issue_id,request_id,prompt,answer,requested_at,answered_at,disposition,disposition_reason,disposed_at
            FROM fb_issue_supplement WHERE request_id=#{request}
            ORDER BY pending_request IS NOT NULL DESC,requested_at DESC,id DESC LIMIT 20
            """)
    List<Supplement> forRequest(String request);

    /**
     * 主事项收拢所有成员的待补充及近期回复，正文由服务层按原问题裁剪。
     */
    @Select(
            """
            SELECT s.id,s.issue_id,s.request_id,s.prompt,s.answer,s.requested_at,s.answered_at,
                s.disposition,s.disposition_reason,s.disposed_at
            FROM fb_issue_supplement s JOIN fb_issue i ON i.id=s.issue_id
            WHERE i.id=#{issue} OR i.merged_into=#{issue}
            ORDER BY s.pending_request IS NOT NULL DESC,s.requested_at DESC,s.id DESC
            """)
    List<Supplement> forIssue(long issue);

    /**
     * 同一原问题同时只有一个待完成的补充请求。
     */
    @Select(
            """
            SELECT id,issue_id,request_id,prompt,answer,requested_at,answered_at,disposition,disposition_reason,disposed_at
            FROM fb_issue_supplement WHERE pending_request=#{request}
            """)
    Supplement pending(String request);

    /**
     * 定位确切的补充记录及其所属原事项，用于核对当前主从关系。
     */
    @Select(
            """
            SELECT id,issue_id,request_id,prompt,answer,requested_at,answered_at,disposition,disposition_reason,disposed_at
            FROM fb_issue_supplement WHERE id=#{id}
            """)
    Supplement supplement(String id);

    /**
     * 请求补充和主动说明共用原问题关联，主动说明直接记录回复时间。
     */
    @Insert(
            """
            INSERT INTO fb_issue_supplement(id,issue_id,request_id,prompt,answer,answered_at)
            VALUES(#{id},#{issue},#{request},#{prompt},#{answer},
                CASE WHEN #{answer} IS NULL THEN NULL ELSE UTC_TIMESTAMP(3) END)
            """)
    void appendSupplement(String id, long issue, String request, String prompt, String answer);

    /**
     * 回复和事项版本一次提交，旧请求或重复提交不能覆盖已收说明。
     */
    @Update(
            """
            UPDATE fb_issue_supplement SET answer=#{answer},answered_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND pending_request=#{request}
            """)
    int answer(String id, String request, String answer);

    /**
     * 只处置仍然待回复的请求，与回复并发时只有一个动作能更新该记录。
     */
    @Update(
            """
            UPDATE fb_issue_supplement
            SET disposition=#{disposition},disposition_reason=#{reason},disposed_by=#{actor},disposed_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND pending_request IS NOT NULL
            """)
    int dispose(String id, long actor, String disposition, String reason);

    /**
     * 已结束请求的原问补充正文与反馈原因使用同一留存策略。
     */
    @Update(
            """
            UPDATE fb_issue_supplement SET prompt=NULL,answer=NULL
            WHERE COALESCE(answered_at,disposed_at)<#{cutoff} AND (prompt IS NOT NULL OR answer IS NOT NULL)
            ORDER BY COALESCE(answered_at,disposed_at),id LIMIT 200
            """)
    int purgeSupplements(LocalDateTime cutoff);

    /**
     * 证据范围变化使当前复验失效，原结果和记录仍可按原版本定位。
     */
    @Update(
            """
            UPDATE fb_issue SET version=version+1,updated_at=UTC_TIMESTAMP(3),
                verification_version=IF(#{invalidate},NULL,verification_version)
            WHERE id=#{issue} AND version=#{version}
            """)
    int advance(long issue, int version, boolean invalidate);

    /**
     * 同一主事项下只要还有一条待回复，就不能结束等待。
     */
    @Select(
            """
            SELECT EXISTS(SELECT 1 FROM fb_issue_supplement s JOIN fb_issue i ON i.id=s.issue_id
                WHERE (i.id=#{issue} OR i.merged_into=#{issue}) AND s.pending_request IS NOT NULL)
            """)
    boolean groupWaiting(long issue);

    /**
     * 补齐后回到处理，结果仍可据实修订和复验。
     */
    @Update("UPDATE fb_issue SET status='IN_PROGRESS' WHERE id=#{issue} AND status='PENDING_VERIFICATION'")
    void resumeHandling(long issue);

    /**
     * 保留从事项原阶段，解除关系时恢复办理。
     */
    @Update(
            """
            UPDATE fb_issue SET pre_merge_status=status,merged_into=#{main},status='MERGED',
                version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{member} AND version=#{version} AND merged_into IS NULL
            """)
    int merge(long member, int version, long main);

    /**
     * 纠正关系后保持独立路由，已有自动去重事项继续接收新增线索。
     */
    @Update(
            """
            UPDATE fb_issue SET status=pre_merge_status,pre_merge_status=NULL,merged_into=NULL,gap_key=NULL,
                verification_version=NULL,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{member} AND version=#{version} AND merged_into=#{main}
            """)
    int unmerge(long member, int version, long main);

    /**
     * 重开开启新轮次，旧结果和结案根据原版本保留。
     */
    @Update(
            """
            UPDATE fb_issue SET completion_round=completion_round+1,status='IN_PROGRESS',gap_key=NULL,
                result_version=NULL,verification_version=NULL,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{issue} AND version=#{version} AND status IN ('RESOLVED','CLOSED_OTHER')
            """)
    int reopen(long issue, int version);

    /**
     * 关系事件同时保存原事项轮次及可追溯的关系另一端。
     */
    @Insert(
            """
            INSERT INTO fb_issue_activity(issue_id,issue_version,actor_id,action,note,related_issue_id,completion_round)
            SELECT i.id,#{version},#{actor},#{action},#{note},#{related},i.completion_round
            FROM fb_issue i WHERE i.id=#{issue}
            """)
    void activity(long issue, int version, long actor, String action, String note, Long related);
}
