package com.dataocean.module.knowledge.mapper;

import com.dataocean.module.governance.service.GovernanceKnowledgeProgress.Target;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 保存撤下批次与冻结成员，原绑定和修订继续可追溯。
 */
public interface KnowledgeWithdrawalMapper {

    /**
     * @param id 撤下批次编号
     * @param reason 本次整批退出的实际依据
     * @param withdrawnBy 正式办理人
     * @param withdrawnAt 实际提交时间
     */
    record Batch(long id, String reason, long withdrawnBy, LocalDateTime withdrawnAt) {}

    /**
     * @param bindingId 原正式绑定
     * @param title 当次冻结的发布名称
     * @param revision 当次退出的文档修订
     * @param depth 相对目标的最短引用距离
     */
    record Member(long bindingId, String title, int revision, int depth) {}

    /**
     * 记录一次整批处理的统一原因和可信命令。
     */
    @Insert(
            """
            INSERT INTO kn_release_withdrawal(id,source_id,target_binding_id,reason,withdrawn_by,command_id)
            VALUES(#{id},#{source},#{target},#{reason},#{actor},#{command})
            """)
    void create(
            @Param("id") long id,
            @Param("source") long source,
            @Param("target") long target,
            @Param("reason") String reason,
            @Param("actor") long actor,
            @Param("command") String command);

    /**
     * 同批成员由提交核对的闭包确定，每个绑定最多退出一次。
     */
    @Insert(
            """
            INSERT INTO kn_release_withdrawal_member(binding_id,withdrawal_id,depth)
            VALUES(#{binding},#{id},#{depth})
            """)
    void add(@Param("id") long id, @Param("binding") long binding, @Param("depth") int depth);

    /**
     * 保留原绑定指针时仍实时判断退出事实。
     */
    @Select("SELECT EXISTS(SELECT 1 FROM kn_release_withdrawal_member WHERE binding_id=#{binding})")
    boolean withdrawn(long binding);

    /**
     * 同源回看某绑定退出时的完整记录。
     */
    @Select(
            """
            SELECT w.id,w.reason,w.withdrawn_by,w.withdrawn_at
            FROM kn_release_withdrawal w JOIN kn_release_withdrawal_member m ON m.withdrawal_id=w.id
            WHERE w.source_id=#{source} AND m.binding_id=#{binding}
            """)
    Batch forBinding(@Param("source") long source, @Param("binding") long binding);

    /**
     * 订阅历史的用户只取得已保存的名称和实际修订。
     */
    @Select(
            """
            SELECT m.binding_id,c.title,r.revision,m.depth FROM kn_release_withdrawal_member m
            JOIN kn_release_binding b ON b.id=m.binding_id
            JOIN kn_release_candidate c ON c.id=b.candidate_id
            JOIN kn_document_revision r ON r.id=b.document_revision_id
            WHERE m.withdrawal_id=#{id} ORDER BY m.depth,m.binding_id
            """)
    List<Member> members(long id);

    /**
     * 知识回写必须有这一实际治理目标在某次正式退出范围中的依据。
     */
    @Select(
            """
            SELECT MAX(w.id) FROM kn_release_withdrawal w
            JOIN kn_release_withdrawal_member m ON m.withdrawal_id=w.id
            JOIN kn_release_binding b ON b.id=m.binding_id
            JOIN kn_document_source_ref f ON f.revision_id=b.document_revision_id
            WHERE w.source_id=#{source} AND f.source_id=#{source}
              AND f.object_id=#{target.objectId} AND f.kind=#{target.kind} AND f.target_id=#{target.targetId}
              AND (#{target.baselineRevisionId} IS NULL OR f.fact_revision_id<=#{target.baselineRevisionId})
            """)
    Long withdrawalFor(@Param("source") long source, @Param("target") Target target);

    /**
     * 新依据已写入生效指针但索引仍待核实时，来源事项继续等待正式结果。
     */
    @Select(
            """
            SELECT EXISTS(
                SELECT 1 FROM kn_document_source_ref f JOIN kn_document_revision r ON r.id=f.revision_id
                JOIN kn_document d ON d.id=r.document_id
                LEFT JOIN kn_release_scope s ON s.source_id=d.source_id AND s.document_id=d.id
                LEFT JOIN kn_release_binding b ON b.id=s.active_binding_id
                LEFT JOIN kn_release_withdrawal_member m ON m.binding_id=b.id
                WHERE d.source_id=#{source} AND f.source_id=#{source} AND m.binding_id IS NULL
                  AND f.object_id=#{target.objectId} AND f.kind=#{target.kind} AND f.target_id=#{target.targetId}
                  AND (r.id=d.head_revision_id OR r.id=d.confirmed_revision_id OR r.id=b.document_revision_id)
                  AND (#{target.baselineRevisionId} IS NULL OR f.fact_revision_id<=#{target.baselineRevisionId}
                       OR (b.document_revision_id=r.id AND (b.verified_at IS NULL OR EXISTS (
                            SELECT 1 FROM kn_release_pause p WHERE p.scope_id=s.id AND p.active_marker=1))))
                UNION ALL
                SELECT 1 FROM kn_release_task t JOIN kn_release_scope s ON s.id=t.scope_id
                LEFT JOIN kn_release_candidate c ON c.id=t.candidate_id
                JOIN kn_document_source_ref f ON f.revision_id=t.document_revision_id
                WHERE s.source_id=#{source} AND f.source_id=#{source} AND t.canceled=FALSE
                  AND (c.id IS NULL OR c.status NOT IN ('PUBLISHED','CANCELED','SUPERSEDED'))
                  AND f.object_id=#{target.objectId} AND f.kind=#{target.kind} AND f.target_id=#{target.targetId}
                  AND (#{target.baselineRevisionId} IS NULL OR f.fact_revision_id<=#{target.baselineRevisionId})
            )
            """)
    boolean pendingKnowledge(@Param("source") long source, @Param("target") Target target);
}
