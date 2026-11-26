package com.dataocean.module.query.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 对话只用于拥有者同源的轮次导航与上下文边界。
 */
public interface QueryConversationMapper {

    /** 可回读的业务对话和最新轮次定位。 */
    record Conversation(
            String id,
            long userId,
            long sourceId,
            String title,
            String latestRequestId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    /** 事务内锁定对话，串行接受同源轮次。 */
    @Select("SELECT * FROM qry_conversation WHERE id=#{id} FOR UPDATE")
    Conversation lock(String id);

    /** 同一账号的公开入口也以路径编号、本人及来源共同限制。 */
    @Select("SELECT * FROM qry_conversation WHERE id=#{id}")
    Conversation get(String id);

    /** 已核验当前源访问权后列出该源下的对话。 */
    @Select(
            """
            SELECT * FROM qry_conversation WHERE user_id=#{user} AND source_id=#{source}
            ORDER BY updated_at DESC, id DESC LIMIT 40
            """)
    List<Conversation> history(long user, long source);

    /** 仅在新对话首轮真正接收时创建对话。 */
    @Insert(
            """
            INSERT INTO qry_conversation(id,user_id,source_id,title,latest_request_id)
            VALUES(#{id},#{user},#{source},LEFT(#{title},120),#{request})
            """)
    void insert(String id, long user, long source, String title, String request);

    /** 命令重试不移动原有对话的指针。 */
    @Update(
            """
            UPDATE qry_conversation SET latest_request_id=#{request},updated_at=UTC_TIMESTAMP(3) WHERE id=#{id}
            """)
    void use(String id, String request);

    /** 查询同一对话的最新可定位轮次，不返回答案数据或 SQL。 */
    @Select(
            """
            SELECT id,question,state,created_at FROM qry_request
            WHERE conversation_id=#{conversation}
            ORDER BY created_at DESC,id DESC LIMIT 40
            """)
    List<QueryRequestMapper.History> turns(String conversation);
}
