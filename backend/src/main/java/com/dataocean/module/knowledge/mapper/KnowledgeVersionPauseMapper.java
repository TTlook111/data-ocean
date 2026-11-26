package com.dataocean.module.knowledge.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 保存当前范围的使用限制和明确解除记录。
 */
public interface KnowledgeVersionPauseMapper {

    /**
     * 同一暂停从产生到解除的可信记录，当前运行继续指向原始证据。
     * @param id 暂停记录编号
     * @param scopeId 使用限制所属发布范围
     * @param bindingId 办理暂停时看到的生效绑定
     * @param reason 本次暂停原因
     * @param pausedBy 暂停办理人
     * @param pausedAt 暂停提交时间
     * @param currentRunId 最近一次隔离验证编号
     * @param runBindingId 最近一次验证实际使用的绑定
     * @param restoreReason 核对通过且风险已消除的依据
     * @param restoredBy 恢复办理人
     * @param restoredAt 解除限制的实际提交时间
     */
    record Pause(
            long id,
            long scopeId,
            long bindingId,
            String reason,
            long pausedBy,
            LocalDateTime pausedAt,
            String currentRunId,
            Long runBindingId,
            String restoreReason,
            Long restoredBy,
            LocalDateTime restoredAt) {}

    /**
     * 不读取辅助唯一索引列。
     */
    String ROW =
            """
            SELECT p.id,p.scope_id,p.binding_id,p.reason,p.paused_by,p.paused_at,p.current_run_id,
                   v.recovery_binding_id AS run_binding_id,p.restore_reason,p.restored_by,p.restored_at
            FROM kn_release_pause p LEFT JOIN kn_release_validation v ON v.id=p.current_run_id
            """;

    /**
     * 同一范围每次最多存在一条当前暂停。
     */
    @Select(ROW + " WHERE p.scope_id=#{scope} AND p.active_marker=1")
    Pause active(long scope);

    /**
     * 问数门禁和列表只读当前是否存在限制。
     */
    @Select("SELECT EXISTS(SELECT 1 FROM kn_release_pause WHERE scope_id=#{scope} AND active_marker=1)")
    boolean isPaused(long scope);

    /**
     * 详情可按需回看该范围的历史办理。
     */
    @Select(ROW + " WHERE p.scope_id=#{scope} ORDER BY p.id DESC")
    List<Pause> history(long scope);

    /**
     * 暂停持久化与当前绑定分开，已有生效指针无需更新。
     */
    @Insert(
            """
            INSERT INTO kn_release_pause(id,scope_id,binding_id,reason,paused_by)
            VALUES(#{id},#{scope},#{binding},#{reason},#{actor})
            """)
    void create(
            @Param("id") long id,
            @Param("scope") long scope,
            @Param("binding") long binding,
            @Param("reason") String reason,
            @Param("actor") long actor);

    /**
     * 切换本次恢复证据指针，旧运行仍留在历史。
     */
    @Update(
            """
            UPDATE kn_release_pause SET current_run_id=#{run}
            WHERE id=#{id} AND restored_at IS NULL AND current_run_id <=> #{previous}
            """)
    int link(@Param("id") long id, @Param("previous") String previous, @Param("run") String run);

    /**
     * 仅原暂停和当次已核对运行可以解除限制。
     */
    @Update(
            """
            UPDATE kn_release_pause SET restore_reason=#{reason},restored_by=#{actor},restored_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND restored_at IS NULL AND current_run_id=#{run}
            """)
    int restore(
            @Param("id") long id, @Param("run") String run, @Param("reason") String reason, @Param("actor") long actor);
}
