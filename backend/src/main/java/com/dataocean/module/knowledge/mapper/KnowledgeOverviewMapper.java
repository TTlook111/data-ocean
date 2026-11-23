package com.dataocean.module.knowledge.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.overview.OverviewPeriod.Window;
import com.dataocean.common.overview.OverviewSourceVO;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 按真实核实时间和固定运行题次读取知识发布活动。
 */
public interface KnowledgeOverviewMapper {

    /**
     * 只使用已有批次的核实时刻，一批由任一实际成员任务进入原发布结果。
     */
    String BATCHES =
            """
            SELECT CAST(b.id AS CHAR) AS id,b.source_id,c.task_id,c.title,
                b.verified_at AS happened,'PUBLISHED' AS kind,NULL AS run_id
            FROM kn_publication_batch b
            JOIN kn_release_binding v ON v.id=(SELECT MIN(m.binding_id)
                FROM kn_publication_batch_member m WHERE m.batch_id=b.id)
            JOIN kn_release_candidate c ON c.id=v.candidate_id
            WHERE b.verified_at &gt;= #{start} AND b.verified_at &lt; #{until}
            <if test="source != null">AND b.source_id=#{source}</if>
            """;

    /**
     * 题次归运行发起日，按实际已观察的自动结果及当时可知的人工裁定分类。
     */
    String QUESTIONS =
            """
            SELECT CAST(r.id AS CHAR) AS id,s.source_id,c.task_id,c.title,
                v.created_at AS happened,
                CASE WHEN j.created_at &lt; #{asOf} THEN j.decision
                     WHEN r.observed_at &lt; #{asOf} THEN r.state END AS kind,v.id AS run_id
            FROM kn_release_validation v JOIN kn_release_candidate c ON c.id=v.candidate_id
            JOIN kn_release_scope s ON s.id=c.scope_id
            JOIN kn_release_validation_result r ON r.run_id=v.id
            LEFT JOIN kn_release_validation_judgment j ON j.result_id=r.id
            WHERE v.recovery_pause_id IS NULL AND v.created_at &gt;= #{start} AND v.created_at &lt; #{until}
            <if test="source != null">AND s.source_id=#{source}</if>
            """;

    /**
     * 时点按业务日期分桶，批次和验证异常分别聚合。
     */
    @Select(
            """
            <script>
            WITH activity AS (
            """ + BATCHES
                    + """
            UNION ALL
            """ + QUESTIONS
                    + """
            ), buckets AS (
                <foreach collection="windows" item="window" separator=" UNION ALL ">
                    SELECT #{window.index} AS ordinal,CAST(#{window.start} AS DATETIME(3)) AS starts,
                        CAST(#{window.end} AS DATETIME(3)) AS ends
                </foreach>
            )
            SELECT b.ordinal,COALESCE(SUM(a.kind='PUBLISHED'),0) AS published,
                COALESCE(SUM(a.kind='FAILED'),0) AS failed,COALESCE(SUM(a.kind='EXECUTION_ERROR'),0) AS errors
            FROM buckets b LEFT JOIN activity a ON a.happened &gt;= b.starts AND a.happened &lt; b.ends
            GROUP BY b.ordinal ORDER BY b.ordinal
            </script>
            """)
    List<Volume> totals(
            @Param("source") Long source,
            @Param("start") LocalDateTime start,
            @Param("until") LocalDateTime until,
            @Param("asOf") LocalDateTime asOf,
            @Param("windows") List<Window> windows);

    /**
     * 分页核对实际发布批次或逐题验证异常，沿用原聚合截止时刻。
     */
    @Select(
            """
            <script>
            WITH activity AS (
                <choose>
                    <when test="metric == 'PUBLISHED'">
            """
                    + BATCHES + """
                    </when>
                    <otherwise>
            """
                    + QUESTIONS
                    + """
                    </otherwise>
                </choose>
            )
            SELECT a.id,a.source_id AS sourceId,s.name AS sourceName,a.task_id AS taskId,a.title,
                a.happened AS occurredAt,a.kind,a.run_id AS runId
            FROM activity a JOIN ds_source s ON s.id=a.source_id
            WHERE a.kind=#{metric}
            ORDER BY a.happened DESC,a.id DESC
            </script>
            """)
    IPage<Activity> details(
            Page<Activity> page,
            @Param("source") Long source,
            @Param("start") LocalDateTime start,
            @Param("until") LocalDateTime until,
            @Param("asOf") LocalDateTime asOf,
            @Param("metric") String metric);

    /**
     * 统计来源仅返回后台能够显示的标签。
     */
    @Select("SELECT CAST(id AS CHAR) AS id,name FROM ds_source ORDER BY name,id")
    List<OverviewSourceVO> sources();

    /**
     * 用实际台账时钟固定读取窗口。
     */
    @Select("SELECT UTC_TIMESTAMP(3)")
    LocalDateTime now();

    /**
     * 每个桶的期间发生量。
     *
     * @param ordinal 业务窗口序号
     * @param published 已核实发布批次数
     * @param failed 验证明确未通过题次
     * @param errors 实际执行异常题次
     */
    record Volume(int ordinal, long published, long failed, long errors) {}

    /**
     * 可定位既有发布任务的贡献事实。
     *
     * @param id 批次编号或验证题次编号
     * @param sourceId 来源稳定编号
     * @param sourceName 来源展示名
     * @param taskId 原发布任务稳定编号
     * @param title 冻结文档标题
     * @param occurredAt 核实时刻或验证发起时刻
     * @param kind 统计分类
     * @param runId 原逐题验证轮次
     */
    record Activity(
            String id,
            long sourceId,
            String sourceName,
            long taskId,
            String title,
            LocalDateTime occurredAt,
            String kind,
            String runId) {}
}
