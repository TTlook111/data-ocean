package com.dataocean.module.feedback.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

/** 自动来源只记录原问关系，根据确切失效状态回看证据。 */
public interface FeedbackQueryIssueMapper {

    /** 后台先拿受控状态和定位字段，原话另由问数核权。 */
    record Incident(
            String requestId,
            String state,
            String phase,
            String diagnostic,
            String executionState,
            LocalDateTime failedAt) {}

    /** 同一原问的自动来源只建立一次证据关联。 */
    @Select("SELECT issue_id FROM fb_issue_query_evidence WHERE request_id=#{request}")
    Long linkedIssue(String request);

    /** 已能确认的开放缺口共用责任，新增证据才推进事项版本。 */
    @Insert(
            """
            INSERT INTO fb_issue(source_id,origin,origin_request_id,origin_answer_revision,status,version,direction,gap_key)
            VALUES(#{source},#{origin},#{request},NULL,'PENDING_ASSIGNMENT',1,#{direction},#{gap})
            ON DUPLICATE KEY UPDATE version=version+1,updated_at=UTC_TIMESTAMP(3)
            """)
    void intake(long source, String origin, String request, String direction, String gap);

    /** 未判明同一缺口和每次技术失败分别按本轮原问定位。 */
    @Select("SELECT id FROM fb_issue WHERE origin=#{origin} AND origin_request_id=#{request}")
    Long atRequest(String origin, String request);

    /** 分派、处理中的缺口继续接收同范围线索，结案后可生成下一事项。 */
    @Select("SELECT id FROM fb_issue WHERE source_id=#{source} AND open_gap_key=#{key} FOR UPDATE")
    Long openGap(long source, String key);

    /** 绑定正式原问，动作、回执与该关联在同一事务提交。 */
    @Insert("INSERT INTO fb_issue_query_evidence(request_id,issue_id) VALUES(#{request},#{issue})")
    void link(String request, long issue);

    /** 基础诊断从当前原问状态读取，最多显示最近 20 条。 */
    @Select(
            """
            SELECT q.id AS request_id,q.state,q.failure_phase AS phase,q.failure_code AS diagnostic,
                q.execution_state,q.updated_at AS failed_at
            FROM fb_issue_query_evidence e JOIN qry_request q ON q.id=e.request_id
            WHERE e.issue_id=#{issue} ORDER BY e.linked_at DESC,e.request_id DESC LIMIT 20
            """)
    List<Incident> incidents(long issue);
}
