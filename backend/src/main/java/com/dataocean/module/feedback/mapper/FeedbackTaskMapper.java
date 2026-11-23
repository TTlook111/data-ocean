package com.dataocean.module.feedback.mapper;

import com.dataocean.module.feedback.mapper.FeedbackMapper.Activity;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 事项任务及其当前处理事实，写操作由主事项行锁串行。
 */
public interface FeedbackTaskMapper {

    /**
     * 同一事项下独立分派、核查、提交和复验的任务投影。
     */
    record Task(
            /**
             * 任务所属的主事项。
             */
            long issueId,
            /**
             * 事项内的稳定任务编号。
             */
            int taskId,
            /**
             * 办理所沿用的治理或技术路径。
             */
            String direction,
            /**
             * 当前可提交本任务结果的负责人。
             */
            long assigneeId,
            /**
             * 详情中用于识别处理人的显示名称。
             */
            String assigneeName,
            /**
             * 分派时指定的任务范围，返回前检查证据权限。
             */
            String scopeText,
            /**
             * 决定办理义务的当期任务状态。
             */
            String status,
            /**
             * 当前负责人是否记录本轮核查。
             */
            boolean reviewed,
            /**
             * 当期处理结果在事项台账中所处的版本。
             */
            Integer resultVersion,
            /**
             * 当期人工复验在事项台账中所处的版本。
             */
            Integer verificationVersion,
            /**
             * 任务范围最近变化的 UTC 时间，治理复验必须在此后启动。
             */
            LocalDateTime recheckAfter,
            /**
             * 等待修复生效、源系统修正或同步时保留责任，但不列为当前待办。
             */
            boolean waitingExternal) {}

    /**
     * 详情和写事务使用相同的任务投影。写操作在主事项锁内使用读已提交，避免空任务范围的间隙锁影响其他事项。
     */
    @Select(
            """
            <script>
            SELECT t.issue_id,t.task_id,t.direction,t.assignee_id,u.display_name AS assignee_name,
                t.scope_text,t.status,t.reviewed,t.result_version,t.verification_version,t.recheck_after,t.waiting_external
            FROM fb_issue_task t JOIN iam_user u ON u.id=t.assignee_id
            WHERE t.issue_id=#{issue} ORDER BY t.task_id
            <if test="lock">FOR UPDATE OF t</if>
            </script>
            """)
    List<Task> list(long issue, boolean lock);

    /**
     * 从事项台账取得每条任务当期的跟进内容。
     */
    record Progress(
            /**
             * 当前事项内的任务编号。
             */
            int taskId,
            /**
             * 已做工作，证据受限时为空。
             */
            String workDone,
            /**
             * 下一步安排，证据受限时为空。
             */
            String nextStep) {}

    /**
     * 只在任务最新的非核查动作仍是跟进时返回正文。按本轮查找，与事项历史显示条数无关。
     */
    @Select(
            """
            SELECT a.task_id,CASE WHEN #{includeText} THEN a.note END AS work_done,
                CASE WHEN #{includeText} THEN a.root_cause END AS next_step
            FROM fb_issue_activity a
            JOIN (
                SELECT history.task_id,MAX(history.issue_version) AS issue_version
                FROM fb_issue_activity history
                JOIN fb_issue i ON i.id=history.issue_id AND i.completion_round=history.completion_round
                WHERE history.issue_id=#{issue} AND history.task_id IS NOT NULL AND history.action<>'TASK_REVIEWED'
                GROUP BY history.task_id
            ) latest ON latest.issue_version=a.issue_version AND latest.task_id=a.task_id
            WHERE a.issue_id=#{issue}
                AND a.action IN ('SOURCE_FOLLOW_UP','SOURCE_WAITING','TECHNICAL_FOLLOW_UP','TECHNICAL_WAITING')
            """)
    List<Progress> currentProgress(long issue, boolean includeText);

    /**
     * 建立独立任务，既有单任务的结果可按原版本带入。
     */
    @Insert(
            """
            INSERT INTO fb_issue_task(issue_id,task_id,direction,assignee_id,scope_text,status,reviewed,result_version,
                waiting_external)
            VALUES(#{issueId},#{taskId},#{direction},#{assigneeId},#{scopeText},#{status},#{reviewed},#{resultVersion},
                #{waitingExternal})
            """)
    void insert(Task task);

    /**
     * 转派、退出和复验失败更新当期快照，不删除历史处理记录。
     */
    @Update(
            """
            UPDATE fb_issue_task SET assignee_id=#{assignee},status=#{status},reviewed=#{reviewed},
                result_version=#{result},verification_version=#{verification},waiting_external=#{waitingExternal}
            WHERE issue_id=#{task.issueId} AND task_id=#{task.taskId}
            """)
    void update(
            Task task,
            long assignee,
            String status,
            boolean reviewed,
            Integer result,
            Integer verification,
            boolean waitingExternal);

    /**
     * 任务范围或处理结果变化时使所有旧复验失效，历史按原版本保留。
     */
    @Update(
            """
            UPDATE fb_issue_task SET verification_version=NULL,recheck_after=UTC_TIMESTAMP(3) WHERE issue_id=#{issue}
            """)
    void invalidate(long issue);

    /**
     * 推进主事项版本和阶段，清理原单任务的当前引用。
     */
    @Update(
            """
            UPDATE fb_issue SET status=#{status},result_version=NULL,verification_version=NULL,
                version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{issue} AND version=#{version}
            """)
    int advance(long issue, int version, String status);

    /**
     * 将任务动作附在主事项台账，编号用于区分同方向任务。
     */
    @Insert(
            """
            INSERT INTO fb_issue_activity(issue_id,issue_version,actor_id,action,assignee_id,direction,note,root_cause,
                task_id,completion_round,status_after)
            SELECT i.id,#{event.version},#{event.actorId},#{event.action},#{event.assigneeId},
                #{event.direction},#{event.note},#{event.rootCause},#{task},i.completion_round,
            """
                    + FeedbackMapper.ACTIVITY_STATUS + " FROM fb_issue i WHERE i.id=#{event.issueId}")
    void appendActivity(Activity event, int task);

    /**
     * 重开后在办任务从本轮核查重新开始，退出任务和历史结果不变。
     */
    @Update(
            """
            UPDATE fb_issue_task SET status='IN_PROGRESS',reviewed=FALSE,result_version=NULL,verification_version=NULL,
                waiting_external=FALSE,recheck_after=UTC_TIMESTAMP(3)
            WHERE issue_id=#{issue} AND status IN ('IN_PROGRESS','SUBMITTED')
            """)
    void reopen(long issue);
}
