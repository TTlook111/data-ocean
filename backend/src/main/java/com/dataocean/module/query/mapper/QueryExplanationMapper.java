package com.dataocean.module.query.mapper;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 解释可失败和再次生成，查询结果事实由原请求独立维护。
 */
public interface QueryExplanationMapper {

    /** 独立解释修订的内部状态。 */
    record Explanation(
            String requestId,
            int revision,
            String commandId,
            String status,
            String text,
            Long modelRevisionId,
            String failureCode,
            LocalDateTime leaseUntil,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    /** 当前页面只读最新修订。 */
    @Select("SELECT * FROM qry_explanation WHERE request_id=#{id} ORDER BY revision DESC LIMIT 1")
    Explanation latest(String id);

    /** 相同解释命令在任何时刻都返回原修订。 */
    @Select("SELECT * FROM qry_explanation WHERE request_id=#{id} AND command_id=#{command}")
    Explanation byCommand(String id, String command);

    /** 在事务内与结果或手动重试命令同时接收。 */
    @Insert(
            """
            INSERT INTO qry_explanation(request_id,revision,command_id,status)
            VALUES(#{id},#{revision},#{command},#{status})
            """)
    void add(String id, int revision, String command, String status);

    /** 仅寻找候选，领取前按请求在先、解释在后的顺序锁定。 */
    @Select(
            """
            SELECT * FROM qry_explanation WHERE status='QUEUED' ORDER BY created_at,request_id,revision LIMIT 1
            """)
    Explanation next();

    /** 请求行已经锁定后，读取该解释的当前真实状态。 */
    @Select("SELECT * FROM qry_explanation WHERE request_id=#{id} AND revision=#{revision} FOR UPDATE")
    Explanation lock(String id, int revision);

    /** 绑定独立修订模型和租约，网络或进程未知时只保留失败。 */
    @Update(
            """
            UPDATE qry_explanation SET status='GENERATING',model_revision_id=#{model},
                lease_until=UTC_TIMESTAMP(3)+INTERVAL 40 SECOND,updated_at=UTC_TIMESTAMP(3)
            WHERE request_id=#{id} AND revision=#{revision} AND status='QUEUED'
            """)
    int claim(String id, int revision, Long model);

    /** 只接受同一实际解释修订的结果。 */
    @Update(
            """
            UPDATE qry_explanation SET status=#{status},text=#{text},failure_code=#{code},lease_until=NULL,
                updated_at=UTC_TIMESTAMP(3)
            WHERE request_id=#{id} AND revision=#{revision} AND status='GENERATING'
              AND lease_until>UTC_TIMESTAMP(3)
            """)
    int finish(String id, int revision, String status, String text, String code);

    /** 用户停止解释不改写已交付结果、时间或 SQL 执行编号。 */
    @Update(
            """
            UPDATE qry_explanation SET status='STOPPED',lease_until=NULL,updated_at=UTC_TIMESTAMP(3)
            WHERE request_id=#{id} AND status IN ('QUEUED','GENERATING')
            """)
    void stop(String id);

    /** 中断或过期解释可以由用户明确开启新修订。 */
    @Update(
            """
            UPDATE qry_explanation SET status='FAILED',failure_code='PROCESS_LOST',lease_until=NULL,
                updated_at=UTC_TIMESTAMP(3)
            WHERE status='GENERATING' AND lease_until<=UTC_TIMESTAMP(3)
            """)
    void expire();
}
