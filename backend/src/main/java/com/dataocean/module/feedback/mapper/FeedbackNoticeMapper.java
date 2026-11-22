package com.dataocean.module.feedback.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 站内回告与处理结果独立保存投递、可见及已读事实。 */
public interface FeedbackNoticeMapper {

    /** 站内提示只保存模板标识及确切收件人。 */
    record Notice(
            String id,
            long issueId,
            int completionRound,
            long recipientId,
            String answerId,
            long sourceId,
            String template,
            String state,
            LocalDateTime createdAt,
            LocalDateTime sentAt,
            LocalDateTime readAt) {}

    /** 接收与业务结案和 Outbox 同事务完成。 */
    @Insert(
            """
            INSERT INTO fb_notice(id,issue_id,completion_round,recipient_id,answer_id,source_id,template)
            VALUES(#{id},#{issueId},#{completionRound},#{recipientId},#{answerId},#{sourceId},#{template})
            """)
    void append(Notice notice);

    /** 回告记录用当期完成轮次，后续办理不改写既往消息。 */
    @Select("SELECT * FROM fb_notice WHERE issue_id=#{issue} ORDER BY completion_round DESC LIMIT 1")
    Notice latest(long issue);

    /** 按派发事件的安全编号串行化站内副作用。 */
    @Select("SELECT * FROM fb_notice WHERE id=#{id} FOR UPDATE")
    Notice lock(String id);

    /** 结案事实先落库，后确认站内可见与不可投递情况。 */
    @Update(
            """
            UPDATE fb_notice SET state=#{state},
                sent_at=CASE WHEN #{state}='SUPPRESSED' THEN NULL ELSE UTC_TIMESTAMP(3) END
            WHERE id=#{id} AND state='QUEUED'
            """)
    int deliver(String id, String state);

    /** 产品内收件箱只分页读取本人的已交付回告。 */
    @Select(
            """
            SELECT * FROM fb_notice WHERE recipient_id=#{recipient} AND state IN ('DELIVERED','RESTRICTED')
            ORDER BY sent_at DESC,id DESC
            """)
    IPage<Notice> list(Page<Notice> page, long recipient);

    /** 原回答快速定位只返回仍可读取的最新回告。 */
    @Select(
            """
            SELECT * FROM fb_notice WHERE recipient_id=#{recipient} AND answer_id=#{answer}
                AND state IN ('DELIVERED','RESTRICTED') ORDER BY sent_at DESC,id DESC LIMIT 1
            """)
    Notice forAnswer(long recipient, String answer);

    /** 已读写入只能用当前接收人，重发保留第一次阅读时间。 */
    @Update(
            """
            UPDATE fb_notice SET read_at=COALESCE(read_at,UTC_TIMESTAMP(3))
            WHERE id=#{id} AND recipient_id=#{recipient} AND state IN ('DELIVERED','RESTRICTED')
            """)
    int read(String id, long recipient);

    /** 检查已读回放和当前归属，未知消息返回缺失。 */
    @Select("SELECT * FROM fb_notice WHERE id=#{id} AND recipient_id=#{recipient} AND sent_at IS NOT NULL")
    Notice own(String id, long recipient);
}
