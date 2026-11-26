package com.dataocean.module.feedback.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.overview.OverviewPeriod.Window;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 汇总和下钻共用相同的反馈事实与期末状态区间。
 */
public interface FeedbackOverviewMapper {

    /**
     * 按索引和当前来源限定读取台账，以状态变化重建各自的有效区间。
     */
    String FACTS =
            """
            <script>
            WITH scoped AS (
                SELECT i.id,i.source_id,i.created_at
                FROM fb_issue i
                WHERE i.created_at &lt; #{until} AND i.source_id IN
                <foreach collection="sources" item="source" open="(" separator="," close=")">
                    #{source}
                </foreach>
            ), activities AS (
                SELECT a.issue_id,a.issue_version,a.occurred_at,a.status_after,a.action,
                    CASE
                        WHEN a.action='MERGED' THEN 'MERGED'
                        WHEN a.action='CLOSED' THEN COALESCE(a.status_after,CASE WHEN EXISTS (
                            SELECT 1 FROM fb_notice n WHERE n.issue_id=a.issue_id
                                AND n.completion_round=a.completion_round AND n.template='RESOLVED'
                        ) THEN 'RESOLVED' ELSE 'CLOSED_OTHER' END)
                    END AS closure
                FROM scoped i JOIN fb_issue_activity a ON a.issue_id=i.id
                WHERE a.occurred_at &lt; #{until}
            ), occurrence AS (
                SELECT i.id AS issue_id,0 AS issue_version,i.created_at AS happened,'ADDED' AS kind,
                    NULL AS resolution_ms FROM scoped i
                UNION ALL
                SELECT a.issue_id,a.issue_version,a.occurred_at,a.closure,
                    CASE WHEN a.closure='RESOLVED'
                        THEN TIMESTAMPDIFF(MICROSECOND,i.created_at,a.occurred_at) DIV 1000 END
                FROM activities a JOIN scoped i ON i.id=a.issue_id WHERE a.closure IS NOT NULL
            ), baseline AS (
                SELECT b.* FROM fb_issue_overview_baseline b JOIN scoped i ON i.id=b.issue_id
            ), state_changes AS (
                SELECT id AS issue_id,0 AS issue_version,created_at AS changed,0 AS baseline_order,
                    'PENDING_ASSIGNMENT' AS status FROM scoped
                UNION ALL
                SELECT issue_id,issue_version,captured_at,1,status FROM baseline
                WHERE captured_at &lt; #{until}
                UNION ALL
                SELECT issue_id,issue_version,occurred_at,0,COALESCE(status_after,closure,'IN_PROGRESS')
                FROM activities
                WHERE status_after IS NOT NULL OR closure IS NOT NULL OR action IN ('UNMERGED','REOPENED')
            ), state_intervals AS (
                SELECT issue_id,issue_version,status,changed,
                    LEAD(changed) OVER (
                        PARTITION BY issue_id ORDER BY changed,issue_version,baseline_order
                    ) AS next_change
                FROM state_changes
            )
            """;

    /**
     * 未结和待验证为同一截止状态的两个口径，待验证是未结的子集。
     */
    String OPEN = "('PENDING_ASSIGNMENT','IN_PROGRESS','PENDING_SUPPLEMENT','PENDING_VERIFICATION')";

    /**
     * Java 按业务日期切分区间，SQL 统一统计区间内的发生量和期末存量。
     */
    @Select(
            FACTS
                    + """
            , buckets AS (
                <foreach collection="windows" item="window" separator=" UNION ALL ">
                    SELECT #{window.index} AS ordinal,CAST(#{window.start} AS DATETIME(3)) AS starts,
                        CAST(#{window.end} AS DATETIME(3)) AS ends
                </foreach>
            ), flow AS (
                SELECT b.ordinal,
                    SUM(o.kind='ADDED') AS added,SUM(o.kind='RESOLVED') AS resolved,
                    SUM(o.kind='CLOSED_OTHER') AS other_closed,SUM(o.kind='MERGED') AS merged,
                    SUM(o.resolution_ms) AS resolution_ms
                FROM buckets b JOIN occurrence o ON o.happened &gt;= b.starts AND o.happened &lt; b.ends
                GROUP BY b.ordinal
            ), stock AS (
                SELECT b.ordinal,COUNT(*) AS open_count,SUM(s.status='PENDING_VERIFICATION') AS awaiting
                FROM buckets b JOIN state_intervals s ON s.changed &lt; b.ends
                    AND (s.next_change IS NULL OR s.next_change &gt;= b.ends)
                WHERE s.status IN
            """
                    + OPEN
                    + """
                GROUP BY b.ordinal
            )
            SELECT b.ordinal,COALESCE(f.added,0) AS added,COALESCE(f.resolved,0) AS resolved,
                COALESCE(f.other_closed,0) AS otherClosed,COALESCE(f.merged,0) AS merged,
                COALESCE(s.open_count,0) AS openCount,
                CASE WHEN EXISTS (
                    SELECT 1 FROM baseline legacy JOIN scoped i ON i.id=legacy.issue_id
                    WHERE i.created_at &lt; b.ends AND legacy.captured_at &gt;= b.ends
                ) THEN NULL ELSE COALESCE(s.awaiting,0) END AS awaiting,
                COALESCE(f.resolution_ms,0) AS resolutionMillis
            FROM buckets b LEFT JOIN flow f ON f.ordinal=b.ordinal
                LEFT JOIN stock s ON s.ordinal=b.ordinal
            ORDER BY b.ordinal
            </script>
            """)
    List<Totals> totals(
            @Param("sources") List<Long> sources,
            @Param("until") LocalDateTime until,
            @Param("windows") List<Window> windows);

    /**
     * 支持单个区间或全期间对账，区间存量使用同一个取截止状态条件。
     */
    @Select(
            FACTS
                    + """
            , matching AS (
                <choose>
                    <when test="kind == 'OPEN' or kind == 'PENDING_VERIFICATION'">
                        SELECT s.issue_id,s.issue_version,s.changed AS happened,#{kind} AS kind,
                            NULL AS resolution_ms
                        FROM state_intervals s
                        WHERE (s.next_change IS NULL OR s.next_change &gt;= #{until})
                        <choose>
                            <when test="kind == 'OPEN'">AND s.status IN
            """
                    + OPEN
                    + """
                            </when>
                            <otherwise>
                                AND s.status='PENDING_VERIFICATION' AND NOT EXISTS (
                                    SELECT 1 FROM baseline WHERE captured_at &gt;= #{until}
                                )
                            </otherwise>
                        </choose>
                    </when>
                    <otherwise>
                        SELECT issue_id,issue_version,happened,kind,resolution_ms FROM occurrence
                        WHERE kind=#{kind} AND happened &gt;= #{start}
                    </otherwise>
                </choose>
            )
            SELECT m.issue_id AS issueId,m.issue_version AS issueVersion,s.name AS sourceName,m.happened AS occurredAt,m.kind,
                m.resolution_ms AS resolutionMillis
            FROM matching m JOIN scoped i ON i.id=m.issue_id JOIN ds_source s ON s.id=i.source_id
            ORDER BY m.happened DESC,m.issue_id DESC,m.issue_version DESC
            </script>
            """)
    IPage<DetailRow> details(
            Page<DetailRow> page,
            @Param("sources") List<Long> sources,
            @Param("start") LocalDateTime start,
            @Param("until") LocalDateTime until,
            @Param("kind") String kind);

    /**
     * 以平台库时钟统一截止时间与业务事件时间。
     */
    @Select("SELECT UTC_TIMESTAMP(3)")
    LocalDateTime now();

    /**
     * 单个区间统计，需要保留解决耗时累计值供跨期间加权。
     */
    record Totals(
            /** 关联回业务日期的区间顺序。 */
            int ordinal,
            /** 新建事项数。 */
            long added,
            /** 实际解决结案次数。 */
            long resolved,
            /** 其他结案次数。 */
            long otherClosed,
            /** 合并次数。 */
            long merged,
            /** 截至区间末的未结事项数。 */
            long openCount,
            /** 截至区间末的待验证数，历史不足可为空。 */
            Long awaiting,
            /** 已解决事项的累计处理毫秒数。 */
            long resolutionMillis) {}

    /**
     * 只读取对账所需的注册信息和事实时间。
     */
    record DetailRow(
            /** 可定位事项的稳定编号。 */
            long issueId,
            /** 同一事项多次贡献的独立版本。 */
            int issueVersion,
            /** 来源的当前展示名。 */
            String sourceName,
            /** 创建、结案或进入期末分类的 UTC 时刻。 */
            LocalDateTime occurredAt,
            /** 进入统计的事件或状态编码。 */
            String kind,
            /** 解决耗时毫秒，非已解决分类为空。 */
            Long resolutionMillis) {}
}
