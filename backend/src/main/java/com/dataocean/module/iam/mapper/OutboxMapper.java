package com.dataocean.module.iam.mapper;

import com.dataocean.module.iam.entity.po.OutboxPO;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 公共契约的持久化适配，业务状态和权限由所属服务核验。
 */
public interface OutboxMapper {

    /**
     * 在所属业务事务内保存明确类型记录。
     */
    @Insert(
            "INSERT INTO sys_outbox(event_id,event_type,aggregate_type,aggregate_id,aggregate_version,command_id,payload,available_at,occurred_at) VALUES(#{d.eventId},#{d.eventType},#{d.aggregateType},#{d.aggregateId},#{d.aggregateVersion},#{d.commandId},#{d.payload},UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))")
    int insert(@Param("d") OutboxPO data);

    /**
     * 有界领取到期或租约过期事件，不锁住其他派发进程已持有的记录。
     */
    @Select(
            "SELECT * FROM sys_outbox WHERE (delivery_state='PENDING' AND available_at<=UTC_TIMESTAMP(3)) OR (delivery_state='CLAIMED' AND leased_until<UTC_TIMESTAMP(3)) ORDER BY occurred_at,event_id LIMIT 10 FOR UPDATE SKIP LOCKED")
    List<OutboxPO> pending();

    /**
     * 以新的随机租约领取事件，派发确认必须匹配该租约。
     */
    @Update(
            "UPDATE sys_outbox SET delivery_state='CLAIMED',lease_token=#{lease},leased_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND),attempts=attempts+1 WHERE event_id=#{id}")
    int claim(@Param("id") String id, @Param("lease") String lease);

    /**
     * 仅由持有当前租约者确认事件送达，旧领取不能确认新租约。
     */
    @Update(
            "UPDATE sys_outbox SET delivery_state='DELIVERED',delivered_at=UTC_TIMESTAMP(3),lease_token=NULL,leased_until=NULL WHERE event_id=#{id} AND lease_token=#{lease} AND delivery_state='CLAIMED'")
    int delivered(@Param("id") String id, @Param("lease") String lease);

    /**
     * 只有当前租约允许将派发故障登记为稍后重试。
     */
    @Update(
            "UPDATE sys_outbox SET delivery_state='PENDING',available_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 5 SECOND),last_error_code='DEPENDENCY_UNAVAILABLE',lease_token=NULL,leased_until=NULL WHERE event_id=#{id} AND lease_token=#{lease} AND delivery_state='CLAIMED'")
    int retry(@Param("id") String id, @Param("lease") String lease);

    /**
     * 检查该事件是否已有指定消费者的完成记录。
     */
    @Select("SELECT COUNT(*) FROM sys_event_receipt WHERE event_id=#{id} AND consumer_code=#{consumer}")
    int received(@Param("id") String id, @Param("consumer") String consumer);

    /**
     * 确认当前消费者已完成该事件，重复投递按事件与消费者防重。
     */
    @Insert(
            "INSERT INTO sys_event_receipt(event_id,consumer_code,completed_at) VALUES(#{id},#{consumer},UTC_TIMESTAMP(3))")
    int receipt(@Param("id") String id, @Param("consumer") String consumer);
}
