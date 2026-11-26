package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.overview.OverviewPeriod.Window;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 接入统计与下钻使用同一来源、终态样本和待核实区间。
 */
public interface SourceOverviewMapper {

    /**
     * 终态按结束时间归组，只有实际开始的成功任务参与均值。
     */
    String TASKS =
            """
            SELECT t.id,t.source_id,t.task_no,t.state,t.ended_at,
                TIMESTAMPDIFF(MICROSECOND,t.started_at,t.ended_at) / 1000000.0 AS elapsed
            FROM ds_collection_task t
            WHERE t.ended_at &gt;= #{start} AND t.ended_at &lt; #{until}
            <if test="source != null">AND t.source_id=#{source}</if>
            """;

    /**
     * 原待核实项从发现起有效；首次核实、系统恢复或后继发现界定退出。
     */
    String CHANGES =
            """
            SELECT c.id,c.source_id,c.discovered_at,c.fact_state,
                (SELECT MIN(r.reviewed_at) FROM ds_change_review r
                    WHERE r.change_id=c.id AND r.source_id=c.source_id) AS reviewed_at,
                next_change.discovered_at AS superseded_at
            FROM ds_change c
            LEFT JOIN ds_change next_change ON next_change.id=c.successor_change_id
                AND next_change.source_id=c.source_id
            WHERE c.discovered_at &lt; #{until}
            <if test="source != null">AND c.source_id=#{source}</if>
            """;

    /**
     * 复用同一待核实判定，进入时间和两个退出事件分别按实际台账核实。
     */
    String PENDING = """
            (c.fact_state='PENDING' OR c.reviewed_at IS NOT NULL)
            """;

    /**
     * 期末存量从原始事实回看，不加总每日存量；耗时从原样本计算。
     */
    @Select(
            """
            <script>
            WITH task_facts AS (
            """ + TASKS
                    + """
            ), change_facts AS (
            """ + CHANGES
                    + """
            ), buckets AS (
                <foreach collection="windows" item="window" separator=" UNION ALL ">
                    SELECT #{window.index} AS ordinal,CAST(#{window.start} AS DATETIME(3)) AS starts,
                        CAST(#{window.end} AS DATETIME(3)) AS ends
                </foreach>
            ), task_totals AS (
                SELECT b.ordinal,SUM(t.state='SUCCEEDED') AS succeeded,SUM(t.state='FAILED') AS failed,
                    SUM(t.state='CANCELLED') AS cancelled,
                    AVG(CASE WHEN t.state='SUCCEEDED' THEN t.elapsed END) AS average_seconds
                FROM buckets b JOIN task_facts t ON t.ended_at &gt;= b.starts AND t.ended_at &lt; b.ends
                GROUP BY b.ordinal
            ), change_totals AS (
                SELECT b.ordinal,COUNT(*) AS pending_count
                FROM buckets b JOIN change_facts c ON c.discovered_at &lt; b.ends
                    AND (c.reviewed_at IS NULL OR c.reviewed_at &gt;= b.ends)
                    AND (c.superseded_at IS NULL OR c.superseded_at &gt;= b.ends)
                WHERE
            """
                    + PENDING
                    + """
                GROUP BY b.ordinal
            )
            SELECT b.ordinal,COALESCE(t.succeeded,0) AS succeeded,COALESCE(t.failed,0) AS failed,
                COALESCE(t.cancelled,0) AS cancelled,COALESCE(c.pending_count,0) AS pending,
                t.average_seconds AS averageCollectionSeconds
            FROM buckets b LEFT JOIN task_totals t ON t.ordinal=b.ordinal
                LEFT JOIN change_totals c ON c.ordinal=b.ordinal
            ORDER BY b.ordinal
            </script>
            """)
    List<Totals> totals(
            @Param("source") Long source,
            @Param("start") LocalDateTime start,
            @Param("until") LocalDateTime until,
            @Param("windows") List<Window> windows);

    /**
     * 返回为所选计数或平均值作出贡献的行，待核实取区间截止状态。
     */
    @Select(
            """
            <script>
            WITH matching AS (
                <choose>
                    <when test="metric == 'PENDING'">
                        SELECT CAST(c.id AS CHAR) AS id,c.source_id,NULL AS task_no,'PENDING' AS kind,
                            c.discovered_at AS occurred_at,NULL AS duration_seconds
                        FROM (
            """
                    + CHANGES + """
                        ) c WHERE
            """ + PENDING
                    + """
                            AND (c.reviewed_at IS NULL OR c.reviewed_at &gt;= #{until})
                            AND (c.superseded_at IS NULL OR c.superseded_at &gt;= #{until})
                    </when>
                    <otherwise>
                        SELECT t.id,t.source_id,t.task_no,t.state AS kind,t.ended_at AS occurred_at,
                            t.elapsed AS duration_seconds
                        FROM (
            """
                    + TASKS
                    + """
                        ) t
                        <choose>
                            <when test="metric == 'DURATION'">WHERE t.state='SUCCEEDED' AND t.elapsed IS NOT NULL</when>
                            <otherwise>WHERE t.state=#{metric}</otherwise>
                        </choose>
                    </otherwise>
                </choose>
            )
            SELECT m.id,m.source_id AS sourceId,s.name AS sourceName,m.task_no AS taskNo,m.kind,
                m.occurred_at AS occurredAt,m.duration_seconds AS durationSeconds
            FROM matching m JOIN ds_source s ON s.id=m.source_id
            ORDER BY m.occurred_at DESC,m.id DESC
            </script>
            """)
    IPage<DetailRow> details(
            Page<DetailRow> page,
            @Param("source") Long source,
            @Param("start") LocalDateTime start,
            @Param("until") LocalDateTime until,
            @Param("metric") String metric);

    /**
     * 使用保存采集台账的平台库时钟。
     */
    @Select("SELECT UTC_TIMESTAMP(3)")
    LocalDateTime now();

    /**
     * 区间统计的持久层投影。
     *
     * @param ordinal 对应的分组顺序
     * @param succeeded 成功任务次数
     * @param failed 失败任务次数
     * @param cancelled 已确认取消的任务次数
     * @param pending 期末待事实核实数量
     * @param averageCollectionSeconds 有执行时间的成功采集平均秒数
     */
    record Totals(
            int ordinal, long succeeded, long failed, long cancelled, long pending, Double averageCollectionSeconds) {}

    /**
     * 只读对账投影。
     *
     * @param id 任务 UUID 或变化 ID 的文本
     * @param sourceId 当前来源编号
     * @param sourceName 当前来源名称
     * @param taskNo 采集任务编号
     * @param kind 终态或待核实分类
     * @param occurredAt UTC 任务结束或变化发现时刻
     * @param durationSeconds 实际执行秒数
     */
    record DetailRow(
            String id,
            long sourceId,
            String sourceName,
            String taskNo,
            String kind,
            LocalDateTime occurredAt,
            Double durationSeconds) {}
}
