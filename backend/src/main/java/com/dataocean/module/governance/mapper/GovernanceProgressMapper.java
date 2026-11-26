package com.dataocean.module.governance.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.governance.mapper.GovernanceOverviewMapper.DetailRow;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用期末当时的目录、范围和业务修订复算对象进度。
 */
public interface GovernanceProgressMapper {

    /**
     * 还原当时成功目录及有效 Schema，未知身份须等核实证据落定。
     */
    String PROGRESS =
            """
            <script>
            WITH source_context AS (
                SELECT s.id,
                    (SELECT p.id FROM ds_snapshot p WHERE p.source_id=s.id AND p.status='SUCCEEDED'
                        AND p.committed_at &lt; #{until} ORDER BY p.committed_at DESC,p.id DESC LIMIT 1) AS snapshot_id,
                    (SELECT r.id FROM ds_connection_revision r WHERE r.source_id=s.id AND r.created_at &lt; #{until}
                        ORDER BY r.revision DESC LIMIT 1) AS config_id
                FROM ds_source s WHERE s.created_at &lt; #{until}
                <if test="source != null">AND s.id=#{source}</if>
            ), directory AS (
                SELECT s.id AS source_id,o.namespace,o.source_name,
                    COALESCE(r.resolved_object_id,o.object_id) AS object_id,
                    COALESCE(r.resolved_object_revision_id,o.object_revision_id) AS revision_id
                FROM source_context s JOIN ds_snapshot_object o ON o.snapshot_id=s.snapshot_id
                LEFT JOIN ds_change_review r ON r.change_id=o.identity_change_id AND r.resolved_object_id IS NOT NULL
                    AND r.reviewed_at &lt; #{until}
                WHERE o.read_status='COMPLETE'
                    AND COALESCE(r.resolved_object_id,o.object_id) IS NOT NULL
                    AND (o.namespace='' OR EXISTS (SELECT 1 FROM ds_connection_schema x
                        WHERE x.connection_revision_id=s.config_id AND BINARY x.schema_name=BINARY o.namespace))
            ), item_states AS (
                SELECT i.id,i.source_id,i.object_id,i.kind,i.target_id,
                    (SELECT v.id FROM gov_item_revision v WHERE v.item_id=i.id AND v.created_at &lt; #{until}
                        ORDER BY v.revision DESC LIMIT 1) AS head_revision_id,
                    (SELECT a.item_revision_id FROM gov_confirmation a WHERE a.item_id=i.id
                        AND a.action='CONFIRM' AND a.confirmed_at &lt; #{until}
                        ORDER BY a.confirmed_at DESC,a.id DESC LIMIT 1) AS last_confirmed_revision_id,
                    EXISTS (SELECT 1 FROM gov_impact_task p WHERE p.item_id=i.id AND p.created_at &lt; #{until}
                        AND (p.resolved_at IS NULL OR p.resolved_at &gt;= #{until})) AS open_impact,
                    EXISTS (SELECT 1 FROM gov_gap q WHERE q.item_id=i.id AND q.opened_at &lt; #{until}
                        AND (q.resolved_at IS NULL OR q.resolved_at &gt;= #{until})) AS open_gap
                FROM gov_item i JOIN source_context s ON s.id=i.source_id
                WHERE i.created_at &lt; #{until}
            ), scored AS (
                SELECT d.*,
                    1+(SELECT COUNT(*) FROM ds_object_column c WHERE c.object_revision_id=d.revision_id) AS total_count,
                    (SELECT COUNT(*) FROM item_states i WHERE i.source_id=d.source_id AND i.object_id=d.object_id
                        AND i.head_revision_id=i.last_confirmed_revision_id AND NOT i.open_impact AND NOT i.open_gap
                        AND (i.kind='TABLE' OR EXISTS (SELECT 1 FROM ds_object_column c
                            JOIN ds_column_revision r ON r.id=c.column_revision_id
                            WHERE c.object_revision_id=d.revision_id AND r.column_id=i.target_id))) AS confirmed_count,
                    EXISTS (SELECT 1 FROM item_states i WHERE i.source_id=d.source_id AND i.object_id=d.object_id
                        AND i.head_revision_id IS NOT NULL) AS started
                FROM directory d
            ), progress_facts AS (
                SELECT scored.*,
            """
                    + GovernanceReadMapper.OBJECT_PROGRESS
                    + """
                AS progress FROM scored
            )
            """;

    /**
     * 只在所选期末重建对象进度，环图不累计各日的对象。
     */
    @Select(
            PROGRESS
                    + """
            SELECT COALESCE(SUM(progress='COMPLETE'),0) AS complete,
                COALESCE(SUM(progress='IN_PROGRESS'),0) AS inProgress,
                COALESCE(SUM(progress='UNSTARTED'),0) AS unstarted
            FROM progress_facts
            </script>
            """)
    Progress total(@Param("source") Long source, @Param("until") LocalDateTime until);

    /**
     * 环图对象明细使用完全相同的期末分类。
     */
    @Select(
            PROGRESS
                    + """
            SELECT CAST(p.object_id AS CHAR) AS id,p.source_id AS sourceId,s.name AS sourceName,
                p.object_id AS objectId,p.object_id AS targetId,p.progress AS kind,
                CONCAT_WS('.',NULLIF(p.namespace,''),p.source_name) AS label,
                CAST(#{until} AS DATETIME(3)) AS occurredAt
            FROM progress_facts p JOIN ds_source s ON s.id=p.source_id
            WHERE p.progress=#{metric}
            ORDER BY s.name,p.namespace,p.source_name,p.object_id
            </script>
            """)
    IPage<DetailRow> details(
            Page<DetailRow> page,
            @Param("source") Long source,
            @Param("until") LocalDateTime until,
            @Param("metric") String metric);

    /**
     * 期末治理进度环图的三类计数。
     *
     * @param complete 已治理的对象数
     * @param inProgress 治理中的对象数
     * @param unstarted 待治理的对象数
     */
    record Progress(long complete, long inProgress, long unstarted) {}
}
