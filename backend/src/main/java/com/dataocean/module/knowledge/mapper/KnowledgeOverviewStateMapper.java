package com.dataocean.module.knowledge.mapper;

import com.dataocean.common.overview.OverviewPeriod.Window;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用正式入口事件和可证明的状态时段重建知识期末存量。
 */
public interface KnowledgeOverviewStateMapper {

    /**
     * 发布、回退及撤下共同决定所选时刻的实际绑定。暂停保留自己独立的时间区间。
     */
    @Select(
            """
            <script>
            WITH scopes AS (
                SELECT a.id,a.source_id,a.document_id FROM kn_release_scope a WHERE 1=1
                <if test="source != null">AND a.source_id=#{source}</if>
            ), actions AS (
                SELECT b.scope_id,b.id AS binding_id,b.id AS event_id,b.created_at AS changed,b.verified_at
                FROM kn_release_binding b JOIN scopes a ON a.id=b.scope_id WHERE b.created_at &lt; #{until}
                UNION ALL
                SELECT b.scope_id,b.previous_binding_id,r.id,r.created_at,r.verified_at
                FROM kn_publication_rollback r JOIN kn_publication_batch_member m ON m.batch_id=r.batch_id
                JOIN kn_release_binding b ON b.id=m.binding_id JOIN scopes a ON a.id=b.scope_id
                WHERE r.created_at &lt; #{until}
                UNION ALL
                SELECT b.scope_id,NULL,w.id,w.withdrawn_at,NULL
                FROM kn_release_withdrawal w JOIN kn_release_withdrawal_member m ON m.withdrawal_id=w.id
                JOIN kn_release_binding b ON b.id=m.binding_id JOIN scopes a ON a.id=b.scope_id
                WHERE w.withdrawn_at &lt; #{until}
            ), intervals AS (
                SELECT a.*,LEAD(a.changed) OVER(PARTITION BY a.scope_id ORDER BY a.changed,a.event_id) AS next_at
                FROM actions a
            ), buckets AS (
                <foreach collection="windows" item="window" separator=" UNION ALL ">
                    SELECT #{window.index} AS ordinal,CAST(#{window.end} AS DATETIME(3)) AS ends
                </foreach>
            ), active AS (
                SELECT w.ordinal,w.ends,s.source_id,s.document_id,i.scope_id,i.binding_id,i.verified_at
                FROM buckets w JOIN intervals i ON i.changed &lt; w.ends
                    AND (i.next_at IS NULL OR i.next_at &gt;= w.ends)
                JOIN scopes s ON s.id=i.scope_id
                WHERE i.binding_id IS NOT NULL
            )
            SELECT a.ordinal,a.source_id AS sourceId,s.name AS sourceName,
                a.document_id AS documentId,a.binding_id AS bindingId,v.document_revision_id AS revisionId,c.title,
                (a.verified_at IS NOT NULL AND a.verified_at &lt; a.ends AND v.verified_at IS NOT NULL
                    AND v.verified_at &lt; a.ends AND b.state='SUCCEEDED'
                    AND b.finished_at IS NOT NULL AND b.finished_at &lt; a.ends) AS verified,
                EXISTS (SELECT 1 FROM kn_release_pause p WHERE p.scope_id=a.scope_id AND p.paused_at &lt; a.ends
                    AND (p.restored_at IS NULL OR p.restored_at &gt;= a.ends)) AS paused,
                EXISTS (SELECT 1 FROM kn_document_revision newer WHERE newer.document_id=a.document_id
                    AND newer.revision &gt; d.revision AND newer.created_at &lt; a.ends) AS updating,
                EXISTS (SELECT 1 FROM kn_document_source_ref f JOIN gov_item i
                    ON i.source_id=f.source_id AND i.object_id=f.object_id AND
                        (i.kind='TABLE' OR (i.kind=f.kind AND i.target_id=f.target_id))
                    WHERE f.revision_id=v.document_revision_id AND 'FORBID'=(
                        SELECT e.action FROM gov_usage_control_event e WHERE e.item_id=i.id AND e.occurred_at &lt; a.ends
                        ORDER BY e.control_version DESC LIMIT 1)) AS forbidden,
                (SELECT e.enabled FROM mdl_model_use_event e WHERE e.model_id=cfg.model_id AND e.effective_at &lt; a.ends
                    ORDER BY e.effective_at DESC,e.model_version DESC LIMIT 1) AS modelEnabled
            FROM active a JOIN ds_source s ON s.id=a.source_id JOIN kn_release_binding v ON v.id=a.binding_id
            JOIN kn_document_revision d ON d.id=v.document_revision_id JOIN kn_release_candidate c ON c.id=v.candidate_id
            JOIN kn_release_build b ON b.id=v.build_id JOIN mdl_config_revision cfg ON cfg.id=v.embedding_revision_id
            ORDER BY a.ordinal,s.name,c.title,a.document_id
            </script>
            """)
    List<State> states(
            @Param("source") Long source, @Param("until") LocalDateTime until, @Param("windows") List<Window> windows);

    /**
     * 只在结构和治理事实都能证明与当前权威读取相同时回放旧绑定的内容门禁。
     */
    @Select(
            """
            <script>
            SELECT s.id AS sourceId,
                GREATEST(s.updated_at,
                    COALESCE((SELECT MAX(r.created_at) FROM ds_connection_revision r WHERE r.source_id=s.id),s.updated_at),
                    COALESCE((SELECT MAX(x.committed_at) FROM ds_snapshot x WHERE x.source_id=s.id),s.updated_at),
                    COALESCE((SELECT MAX(c.updated_at) FROM ds_change c WHERE c.source_id=s.id),s.updated_at),
                    COALESCE((SELECT MAX(i.updated_at) FROM gov_item i WHERE i.source_id=s.id),s.updated_at),
                    COALESCE((SELECT MAX(r.created_at) FROM gov_discovery_revision r JOIN gov_discovery_candidate c
                        ON c.id=r.candidate_id WHERE c.source_id=s.id),s.updated_at),
                    COALESCE((SELECT MAX(u.updated_at) FROM gov_usage_control u JOIN gov_item i
                        ON i.id=u.item_id WHERE i.source_id=s.id),s.updated_at)) AS changedAt
            FROM ds_source s
            <if test="source != null">WHERE s.id=#{source}</if>
            </script>
            """)
    List<Basis> basis(@Param("source") Long source);

    /**
     * 每个分组真实有效的文档及独立维护状态。
     *
     * @param ordinal 业务窗口序号
     * @param sourceId 来源
     * @param sourceName 当前来源标签
     * @param documentId 稳定文档
     * @param bindingId 截止点的正式绑定
     * @param revisionId 当时实际生效的文档修订
     * @param title 绑定冻结标题
     * @param verified 实际入口与索引代次均已核实
     * @param paused 此范围有仍在生效的暂停
     * @param updating 已存在较新的保存修订
     * @param forbidden 真实治理限制适用
     * @param modelEnabled 可证实的时点模型状态，历史缺失时为空
     */
    record State(
            int ordinal,
            long sourceId,
            String sourceName,
            long documentId,
            long bindingId,
            long revisionId,
            String title,
            boolean verified,
            boolean paused,
            boolean updating,
            boolean forbidden,
            Boolean modelEnabled) {}

    /**
     * 当前治理及结构读取可支持的最早保守时点。
     *
     * @param sourceId 事实所属来源
     * @param changedAt 当前来源及治理事实最近变动时刻
     */
    record Basis(long sourceId, LocalDateTime changedAt) {}
}
