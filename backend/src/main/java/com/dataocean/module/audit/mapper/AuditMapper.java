package com.dataocean.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.audit.entity.po.AuditPO;
import com.dataocean.module.audit.entity.query.AuditQuery;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 历史审计的持久化适配，业务状态和权限由所属服务核验。
 */
public interface AuditMapper extends BaseMapper<AuditPO> {

    /**
     * 核对本次命令与真实动作、目标是否已有对应成功审计。
     */
    default long recorded(long actor, String command, String action, String target) {
        return selectCount(Wrappers.<AuditPO>lambdaQuery()
                .eq(AuditPO::getActorUserId, actor)
                .eq(AuditPO::getCommandId, command)
                .eq(AuditPO::getActionCode, action)
                .eq(AuditPO::getTargetId, target)
                .eq(AuditPO::getOutcome, "SUCCESS"));
    }

    /**
     * 列表与总数共用的参数化过滤片段，文本条件按字面转义。
     * 文本块续行消除可选节点间的空行，避免分页解析截断后续筛选条件。
     */
    String FILTER =
            """
        <where>\
        <foreach collection="q.actorTerms" item="term">AND (JSON_UNQUOTE(JSON_EXTRACT(a.actor_snapshot,'$.username')) COLLATE utf8mb4_0900_ai_ci LIKE #{term} ESCAPE '!' OR JSON_UNQUOTE(JSON_EXTRACT(a.actor_snapshot,'$.displayName')) COLLATE utf8mb4_0900_ai_ci LIKE #{term} ESCAPE '!')</foreach>\
        <foreach collection="q.targetTerms" item="term">AND (a.target_name COLLATE utf8mb4_0900_ai_ci LIKE #{term} ESCAPE '!' OR a.target_id LIKE #{term} ESCAPE '!')</foreach>\
        <if test="q.actionCode != null and q.actionCode != ''">AND a.action_code=#{q.actionCode}</if>\
        <if test="q.outcome != null and q.outcome != ''">AND a.outcome=#{q.outcome}</if>\
        <if test="from != null">AND a.occurred_at &gt;= #{from} AND a.occurred_at &lt; #{until}</if>\
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
}
