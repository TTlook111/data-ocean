package com.dataocean.module.governance.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.overview.OverviewPeriod.Window;
import com.dataocean.common.overview.OverviewSourceVO;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 治理确认事件与复核区间用于趋势和相同口径的下钻。
 */
public interface GovernanceOverviewMapper {

    /**
     * 每次真实确认保留原结构或定义名称，草稿及确认移除不参与统计。
     */
    String CONFIRMATIONS =
            """
            SELECT CAST(c.id AS CHAR) AS id,g.source_id,g.object_id,g.target_id,g.kind,
                COALESCE(NULLIF(CONCAT_WS('.',NULLIF(o.namespace,''),o.source_name,f.source_name),''),
                    CONCAT('#',g.target_id)) AS label,
                c.confirmed_at AS happened
            FROM gov_confirmation c JOIN gov_item g ON g.id=c.item_id
            LEFT JOIN ds_object_revision o ON o.id=c.object_revision_id
            LEFT JOIN ds_column_revision f ON f.id=c.column_revision_id
            WHERE c.action='CONFIRM' AND c.confirmed_at &gt;= #{start} AND c.confirmed_at &lt; #{until}
            <if test="source != null">AND g.source_id=#{source}</if>
            UNION ALL
            SELECT CAST(r.id AS CHAR),c.source_id,c.object_id,c.id,c.kind,
                JSON_UNQUOTE(JSON_EXTRACT(r.content,'$.name')),r.created_at
            FROM gov_discovery_revision r JOIN gov_discovery_candidate c ON c.id=r.candidate_id
            WHERE r.state='CONFIRMED' AND r.created_at &gt;= #{start} AND r.created_at &lt; #{until}
            <if test="source != null">AND c.source_id=#{source}</if>
            """;

    /**
     * 每个明确要求复核的来源有独立区间，统计时再按业务条目去重。
     */
    String RECHECKS =
            """
            SELECT t.source_id,t.item_id,t.created_at,t.resolved_at
            FROM gov_impact_task t
            WHERE t.impact_kind='RECHECK' AND t.created_at &lt; #{until}
            <if test="source != null">AND t.source_id=#{source}</if>
            """;

    /**
     * 计算期间确认发生量和各分组结束前实际仍未核对的条目数。
     */
    @Select(
            """
            <script>
            WITH confirmations AS (
            """ + CONFIRMATIONS
                    + """
            ), rechecks AS (
            """ + RECHECKS
                    + """
            ), buckets AS (
                <foreach collection="windows" item="window" separator=" UNION ALL ">
                    SELECT #{window.index} AS ordinal,CAST(#{window.start} AS DATETIME(3)) AS starts,
                        CAST(#{window.end} AS DATETIME(3)) AS ends
                </foreach>
            ), confirmation_totals AS (
                SELECT b.ordinal,SUM(e.kind='TABLE') AS table_count,SUM(e.kind='COLUMN') AS field_count,
                    SUM(e.kind='RELATION') AS relation_count,SUM(e.kind='METRIC') AS metric_count
                FROM buckets b JOIN confirmations e ON e.happened &gt;= b.starts AND e.happened &lt; b.ends
                GROUP BY b.ordinal
            ), recheck_totals AS (
                SELECT b.ordinal,COUNT(DISTINCT p.item_id) AS pending_count
                FROM buckets b JOIN rechecks p ON p.created_at &lt; b.ends
                    AND (p.resolved_at IS NULL OR p.resolved_at &gt;= b.ends)
                GROUP BY b.ordinal
            )
            SELECT b.ordinal,COALESCE(c.table_count,0) AS tableConfirmed,COALESCE(c.field_count,0) AS fieldConfirmed,
                COALESCE(c.relation_count,0) AS relationConfirmed,COALESCE(c.metric_count,0) AS metricConfirmed,
                COALESCE(p.pending_count,0) AS pendingReview
            FROM buckets b LEFT JOIN confirmation_totals c ON c.ordinal=b.ordinal
                LEFT JOIN recheck_totals p ON p.ordinal=b.ordinal
            ORDER BY b.ordinal
            </script>
            """)
    List<Counts> totals(
            @Param("source") Long source,
            @Param("start") LocalDateTime start,
            @Param("until") LocalDateTime until,
            @Param("windows") List<Window> windows);

    /**
     * 对账使用同一历史条件，重复影响只显示一条业务条目。
     */
    @Select(
            """
            <script>
            <choose>
                <when test="metric == 'RECHECK'">
                    WITH rechecks AS (
            """
                    + RECHECKS
                    + """
                    ), pending AS (
                        SELECT item_id,MIN(created_at) AS happened FROM rechecks
                        WHERE resolved_at IS NULL OR resolved_at &gt;= #{until}
                        GROUP BY item_id
                    )
                    SELECT CAST(g.id AS CHAR) AS id,g.source_id AS sourceId,s.name AS sourceName,
                        g.object_id AS objectId,g.target_id AS targetId,g.kind,
                        COALESCE(NULLIF(CONCAT_WS('.',NULLIF(o.namespace,''),o.source_name,f.source_name),''),
                            CONCAT('#',g.target_id)) AS label,p.happened AS occurredAt
                    FROM pending p JOIN gov_item g ON g.id=p.item_id
                    JOIN ds_source s ON s.id=g.source_id
                    LEFT JOIN ds_object_revision o ON o.id=(
                        SELECT r.id FROM ds_object_revision r WHERE r.object_id=g.object_id AND r.created_at &lt; #{until}
                        ORDER BY r.revision DESC LIMIT 1)
                    LEFT JOIN ds_column_revision f ON f.id=(
                        SELECT r.id FROM ds_column_revision r WHERE g.kind='COLUMN' AND r.column_id=g.target_id AND r.created_at &lt; #{until}
                        ORDER BY r.revision DESC LIMIT 1)
                    ORDER BY p.happened DESC,g.id DESC
                </when>
                <otherwise>
                    WITH confirmations AS (
            """
                    + CONFIRMATIONS
                    + """
                    )
                    SELECT c.id,c.source_id AS sourceId,s.name AS sourceName,c.object_id AS objectId,
                        c.target_id AS targetId,c.kind,c.label,c.happened AS occurredAt
                    FROM confirmations c JOIN ds_source s ON s.id=c.source_id
                    WHERE c.kind=#{metric}
                    ORDER BY c.happened DESC,c.id DESC
                </otherwise>
            </choose>
            </script>
            """)
    IPage<DetailRow> details(
            Page<DetailRow> page,
            @Param("source") Long source,
            @Param("start") LocalDateTime start,
            @Param("until") LocalDateTime until,
            @Param("metric") String metric);

    /**
     * 看板只公开筛选所需的现有后台来源标签。
     */
    @Select("SELECT CAST(id AS CHAR) AS id,name FROM ds_source ORDER BY name,id")
    List<OverviewSourceVO> sources();

    /**
     * 汇总和下钻都使用平台台账的 UTC 时钟。
     */
    @Select("SELECT UTC_TIMESTAMP(3)")
    LocalDateTime now();

    /**
     * 分组统计的读投影。
     *
     * @param ordinal 窗口编号
     * @param tableConfirmed 表说明确认次数
     * @param fieldConfirmed 字段确认次数
     * @param relationConfirmed 关联确认次数
     * @param metricConfirmed 指标确认次数
     * @param pendingReview 期末去重的待核对条目数
     */
    record Counts(
            int ordinal,
            long tableConfirmed,
            long fieldConfirmed,
            long relationConfirmed,
            long metricConfirmed,
            long pendingReview) {}

    /**
     * 看板贡献记录，可定位原业务对象。
     *
     * @param id 事实或对象编号
     * @param sourceId 来源编号
     * @param sourceName 当前来源名称
     * @param objectId 表或视图的稳定编号
     * @param targetId 指标关联候选、字段或对象编号
     * @param kind 确认动作对象类型或进度
     * @param label 原结构名或业务名称
     * @param occurredAt 确认、复核进入或期末时刻
     */
    record DetailRow(
            String id,
            long sourceId,
            String sourceName,
            long objectId,
            long targetId,
            String kind,
            String label,
            LocalDateTime occurredAt) {}
}
