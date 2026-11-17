package com.dataocean.module.audit.mapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.audit.entity.po.AuditPO;
import com.dataocean.module.audit.entity.query.AuditQuery;


public interface AuditMapper {
    @Select("SELECT COUNT(*) FROM audit_record WHERE actor_user_id=#{actor} AND command_id=#{command} AND action_code=#{action} AND target_id=#{target} AND outcome='SUCCESS'")
    int recorded(@Param("actor") long actor,@Param("command") String command,@Param("action") String action,@Param("target") String target);
    String FILTER="""
    <where>
    <foreach collection="q.actorTerms" item="term">AND (JSON_UNQUOTE(JSON_EXTRACT(a.actor_snapshot,'$.username')) COLLATE utf8mb4_0900_ai_ci LIKE #{term} ESCAPE '!' OR JSON_UNQUOTE(JSON_EXTRACT(a.actor_snapshot,'$.displayName')) COLLATE utf8mb4_0900_ai_ci LIKE #{term} ESCAPE '!')</foreach>
    <foreach collection="q.targetTerms" item="term">AND (a.target_name COLLATE utf8mb4_0900_ai_ci LIKE #{term} ESCAPE '!' OR a.target_id LIKE #{term} ESCAPE '!')</foreach>
    <if test="q.actionCode != null and q.actionCode != ''">AND a.action_code=#{q.actionCode}</if>
    <if test="q.outcome != null and q.outcome != ''">AND a.outcome=#{q.outcome}</if>
    <if test="from != null">AND a.occurred_at &gt;= #{from} AND a.occurred_at &lt; #{until}</if>
    </where>
    """;
    @Select("<script>SELECT a.* FROM audit_record a "+FILTER+"</script>")
    Page<AuditPO> page(Page<AuditPO> page,@Param("q") AuditQuery query,@Param("from") LocalDateTime from,@Param("until") LocalDateTime until);
    @Select("SELECT * FROM audit_record WHERE id=#{id}") AuditPO find(long id);
    @Insert("INSERT INTO audit_record(actor_kind,actor_user_id,actor_snapshot,action_code,target_type,target_id,target_name,outcome,reason,before_summary,after_summary,security_version,command_id,occurred_at) VALUES(#{d.actorKind},#{d.actorUserId},#{d.actorSnapshot},#{d.actionCode},#{d.targetType},#{d.targetId},#{d.targetName},#{d.outcome},#{d.reason},#{d.beforeSummary},#{d.afterSummary},#{d.securityVersion},#{d.commandId},UTC_TIMESTAMP(3))") int insert(@Param("d") AuditPO data);
}
