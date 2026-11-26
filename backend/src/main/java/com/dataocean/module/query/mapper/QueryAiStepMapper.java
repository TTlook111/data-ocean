package com.dataocean.module.query.mapper;

import org.apache.ibatis.annotations.Insert;

/**
 * MySQL 记录本次节点的发起事实，Redis 丢失时不会再次启动原调用。
 */
public interface QueryAiStepMapper {

    /** 只有实际持有当前任务的执行代次能够新登记。 */
    @Insert(
            """
            INSERT IGNORE INTO qry_ai_step(request_id,step)
            SELECT id,#{step} FROM qry_request WHERE id=#{id} AND run_version=#{run}
              AND lease_until>UTC_TIMESTAMP(3) AND cancel_requested=FALSE
            """)
    int issue(String id, int run, String step);

    /** 解释执行许可只对应已领取的一次有限解释修订。 */
    @Insert(
            """
            INSERT IGNORE INTO qry_ai_step(request_id,step)
            SELECT request_id,#{step} FROM qry_explanation WHERE request_id=#{id} AND revision=#{revision}
              AND status='GENERATING' AND lease_until>UTC_TIMESTAMP(3)
            """)
    int issueExplanation(String id, int revision, String step);
}
