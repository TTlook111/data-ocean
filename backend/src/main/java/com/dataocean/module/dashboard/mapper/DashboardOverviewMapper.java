package com.dataocean.module.dashboard.mapper;

import com.dataocean.common.overview.OverviewPeriod.Window;
import com.dataocean.common.overview.OverviewSourceVO;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Select;

/**
 * 读取平台时钟、后台来源标签和正式问数的统计贡献。
 */
public interface DashboardOverviewMapper {

    /**
     * 用平台库 UTC 时钟冻结本次总览。
     */
    @Select("SELECT UTC_TIMESTAMP(3)")
    LocalDateTime now();

    /**
     * 仅供具备全源模块统计权限的调用方列出已有后台来源。
     */
    @Select("SELECT CAST(id AS CHAR) AS id,name FROM ds_source ORDER BY name,id")
    List<OverviewSourceVO> sources();

    /**
     * 按首次提交窗口统计正式问数，后续解释、复验和内部重试不增加请求数。
     */
    @Select(
            """
            <script>
            WITH windows AS (
                <foreach collection="windows" item="w" separator=" UNION ALL ">
                    SELECT #{w.index} AS ordinal, #{w.start} AS from_at, #{w.end} AS to_at
                </foreach>
            )
            SELECT w.ordinal,COUNT(q.id) AS requests,
                COUNT(CASE WHEN q.delivered_at &lt; #{asOf} THEN 1 END) AS delivered
            FROM windows w LEFT JOIN qry_request q
                ON q.created_at &gt;= w.from_at AND q.created_at &lt; w.to_at
                AND q.recheck_origin_id IS NULL
                AND q.source_id IN
                <foreach collection="sources" item="source" open="(" separator="," close=")">
                    #{source}
                </foreach>
            GROUP BY w.ordinal ORDER BY w.ordinal
            </script>
            """)
    List<Usage> usage(List<Long> sources, List<Window> windows, LocalDateTime asOf);

    /**
     * 同一个提交窗口内的请求及截至本次快照已交付的次数。
     *
     * @param ordinal 业务区间序号
     * @param requests 首次提交的正式请求数
     * @param delivered 同批请求的交付数
     */
    record Usage(int ordinal, long requests, long delivered) {}
}
