package com.dataocean.module.audit.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.audit.entity.po.AuditPO;
import com.dataocean.module.audit.entity.query.AuditQuery;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 历史审计的持久化适配，业务状态和权限由所属服务核验。
 */
public interface AuditMapper {

    /**
     * 核对本次命令与真实动作、目标是否已有对应成功审计。
     */
    @Select(
            "SELECT COUNT(*) FROM audit_record WHERE actor_user_id=#{actor} AND command_id=#{command} AND action_code=#{action} AND target_id=#{target} AND outcome='SUCCESS'")
    int recorded(
            @Param("actor") long actor,
            @Param("command") String command,
            @Param("action") String action,
            @Param("target") String target);

    /**
     * 列表与总数共用的参数化过滤片段，文本条件按字面转义。
     */
    String FILTER =
            """
        <where>
        <foreach collection="q.actorTerms" item="term">AND (JSON_UNQUOTE(JSON_EXTRACT(a.actor_snapshot,'$.username')) COLLATE utf8mb4_0900_ai_ci LIKE #{term} ESCAPE '!' OR JSON_UNQUOTE(JSON_EXTRACT(a.actor_snapshot,'$.displayName')) COLLATE utf8mb4_0900_ai_ci LIKE #{term} ESCAPE '!')</foreach>
        <foreach collection="q.targetTerms" item="term">AND (a.target_name COLLATE utf8mb4_0900_ai_ci LIKE #{term} ESCAPE '!' OR a.target_id LIKE #{term} ESCAPE '!')</foreach>
        <if test="q.actionCode != null and q.actionCode != ''">AND a.action_code=#{q.actionCode}</if>
        <if test="q.outcome != null and q.outcome != ''">AND a.outcome=#{q.outcome}</if>
        <if test="from != null">AND a.occurred_at &gt;= #{from} AND a.occurred_at &lt; #{until}</if>
        </where>
        """;

    /**
     * 在数据库中使用同一过滤范围完成排序、统计及分页。
     */
    @Select("<script>SELECT a.* FROM audit_record a " + FILTER + "</script>")
    Page<AuditPO> page(
            Page<AuditPO> page,
            @Param("q") AuditQuery query,
            @Param("from") LocalDateTime from,
            @Param("until") LocalDateTime until);

    /**
     * 按稳定标识读取当前记录，由所属服务继续校验可见性和状态。
     */
    @Select("SELECT * FROM audit_record WHERE id=#{id}")
    AuditPO find(long id);

    /**
     * 在所属业务事务内保存明确类型记录。
     */
    @Insert(
            "INSERT INTO audit_record(actor_kind,actor_user_id,actor_snapshot,action_code,target_type,target_id,target_name,outcome,reason,before_summary,after_summary,security_version,command_id,occurred_at) VALUES(#{d.actorKind},#{d.actorUserId},#{d.actorSnapshot},#{d.actionCode},#{d.targetType},#{d.targetId},#{d.targetName},#{d.outcome},#{d.reason},#{d.beforeSummary},#{d.afterSummary},#{d.securityVersion},#{d.commandId},UTC_TIMESTAMP(3))")
    int insert(@Param("d") AuditPO data);
}
