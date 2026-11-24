package com.dataocean.module.model.mapper;

import com.dataocean.common.overview.OverviewPeriod.Window;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 从诊断共用的全站调用回执读取平台时钟、窗口统计和模型用量。
 */
public interface ModelCallOverviewMapper {

    /**
     * 使用平台库 UTC 毫秒时钟冻结汇总与明细的边界。
     */
    @Select("SELECT UTC_TIMESTAMP(3)")
    LocalDateTime now();

    /**
     * 逐窗口聚合，不以零代替缺失的 Token 回执。
     */
    @Select(
            """
            <script>
            WITH windows AS (
                <foreach collection="windows" item="w" separator=" UNION ALL ">
                    SELECT #{w.index} AS ordinal, #{w.start} AS from_at, #{w.end} AS to_at
                </foreach>
            )
            SELECT w.ordinal, SUM(g.input_tokens) AS input, SUM(g.output_tokens) AS output,
                COUNT(CASE WHEN g.state = 'SUCCEEDED' THEN 1 END) AS succeeded,
                COUNT(CASE WHEN g.state = 'FAILED' AND COALESCE(g.failure_code, '') != 'MODEL_RESULT_UNKNOWN' THEN 1 END) AS failed,
                COUNT(CASE WHEN g.state = 'FAILED' AND g.failure_code = 'MODEL_RESULT_UNKNOWN' THEN 1 END) AS uncertain,
                COUNT(g.id) AS calls,
                COUNT(CASE WHEN g.input_tokens IS NOT NULL AND g.output_tokens IS NOT NULL THEN 1 END) AS complete_usage
            FROM windows w LEFT JOIN mdl_diagnostic_call g
                ON g.recorded_at &gt;= w.from_at AND g.recorded_at &lt; w.to_at
            GROUP BY w.ordinal ORDER BY w.ordinal
            </script>
            """)
    List<Usage> usage(@Param("windows") List<Window> windows);

    /**
     * 跨配置修订汇总实际回执，用区间内最新的同配置快照展示名称。
     */
    @Select(
            """
            WITH known AS (
                SELECT COALESCE(CONCAT('config:',c.model_id), CONCAT('actual:',COALESCE(g.model_name,''))) AS model_id,
                    COALESCE(NULLIF(c.name,''), NULLIF(g.model_name,''), '未知模型') AS name,
                    COALESCE(g.input_tokens,0) + COALESCE(g.output_tokens,0) AS tokens,
                    g.recorded_at, g.id
                FROM mdl_diagnostic_call g LEFT JOIN mdl_config_revision c ON c.id=g.model_revision_id
                WHERE g.recorded_at >= #{window.start} AND g.recorded_at < #{window.end}
                  AND (g.input_tokens IS NOT NULL OR g.output_tokens IS NOT NULL)
            ), ranked AS (
                SELECT model_id,name,SUM(tokens) OVER (PARTITION BY model_id) AS tokens,
                    ROW_NUMBER() OVER (PARTITION BY model_id ORDER BY recorded_at DESC,id DESC) AS row_num
                FROM known
            )
            SELECT model_id AS id,name,tokens FROM ranked WHERE row_num=1 ORDER BY tokens DESC,id
            """)
    List<ModelUsage> ranking(@Param("window") Window window);

    /**
     * 只用于同一统计窗口内的数据库投影。
     *
     * @param ordinal 聚合窗口序号
     * @param input 已知输入 Token 之和
     * @param output 已知输出 Token 之和
     * @param succeeded 成功回执数
     * @param failed 已知失败回执数
     * @param uncertain 结果待核查回执数
     * @param calls 调用回执总数
     * @param completeUsage 输入与输出均有回执的次数
     */
    record Usage(
            int ordinal,
            Long input,
            Long output,
            long succeeded,
            long failed,
            long uncertain,
            long calls,
            long completeUsage) {}

    /**
     * 按配置标识汇总的已知用量。
     *
     * @param id 配置编号或历史实际模型标识
     * @param name 所选期间最新的已知用量快照名称
     * @param tokens 已知输入及输出 Token 合计
     */
    record ModelUsage(String id, String name, long tokens) {}
}
