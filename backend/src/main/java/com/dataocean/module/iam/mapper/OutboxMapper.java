package com.dataocean.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.iam.entity.po.OutboxPO;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 公共契约的持久化适配，业务状态和权限由所属服务核验。
 */
public interface OutboxMapper extends BaseMapper<OutboxPO> {

    /**
     * 与业务同事务追加事件，由同一数据库时刻初始化发生和可领取时间。
     */
    @Insert(
            "INSERT INTO sys_outbox(event_id,event_type,aggregate_type,aggregate_id,aggregate_version,command_id,payload,available_at,occurred_at) VALUES(#{d.eventId},#{d.eventType},#{d.aggregateType},#{d.aggregateId},#{d.aggregateVersion},#{d.commandId},#{d.payload},UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))")
    int append(@Param("d") OutboxPO data);

    /** 恢复反馈时沿用原事件发生时刻，重新计算可领取时间。 */
    @Insert(
            """
            INSERT INTO sys_outbox(event_id,event_type,aggregate_type,aggregate_id,aggregate_version,command_id,
                payload,available_at,occurred_at)
            VALUES(#{d.eventId},#{d.eventType},#{d.aggregateType},#{d.aggregateId},#{d.aggregateVersion},
                #{d.commandId},#{d.payload},UTC_TIMESTAMP(3),#{d.occurredAt})
            """)
    int appendAt(@Param("d") OutboxPO data);

    /** 消费者与补偿共享同一事件行，核对持久租约。 */
    @Select("SELECT * FROM sys_outbox WHERE event_id=#{id} FOR UPDATE")
    OutboxPO lockEvent(String id);

    /** 已投递但缺少本地副作用时，使用原编号重新排队。 */
    @Update(
            """
            UPDATE sys_outbox SET delivery_state='PENDING',available_at=UTC_TIMESTAMP(3),delivered_at=NULL
            WHERE event_id=#{id} AND delivery_state='DELIVERED'
            """)
    int requeue(String id);

    /**
     * 有界领取到期或租约过期事件，不锁住其他派发进程已持有的记录。
     */
    @Select(
            "SELECT * FROM sys_outbox WHERE (delivery_state='PENDING' AND available_at<=UTC_TIMESTAMP(3)) OR (delivery_state='CLAIMED' AND leased_until<UTC_TIMESTAMP(3)) ORDER BY occurred_at,event_id LIMIT 10 FOR UPDATE SKIP LOCKED")
    List<OutboxPO> pending();

    /**
     * 以新的随机租约领取事件，派发确认必须匹配该租约。
     */
    default int claim(String id, String lease) {
        return update(Wrappers.<OutboxPO>lambdaUpdate()
                .eq(OutboxPO::getEventId, id)
                .set(OutboxPO::getDeliveryState, "CLAIMED")
                .set(OutboxPO::getLeaseToken, lease)
                .setIncrBy(OutboxPO::getAttempts, 1)
                .setSql("leased_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND)"));
    }

    /**
     * 仅由持有当前租约者确认事件送达，旧领取不能确认新租约。
     */
    default int delivered(String id, String lease) {
        return update(Wrappers.<OutboxPO>lambdaUpdate()
                .eq(OutboxPO::getEventId, id)
                .eq(OutboxPO::getLeaseToken, lease)
                .eq(OutboxPO::getDeliveryState, "CLAIMED")
                .set(OutboxPO::getDeliveryState, "DELIVERED")
                .set(OutboxPO::getLeaseToken, null)
                .set(OutboxPO::getLeasedUntil, null)
                .setSql("delivered_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 只有当前租约允许将派发故障登记为稍后重试。
     */
    default int retry(String id, String lease) {
        return update(Wrappers.<OutboxPO>lambdaUpdate()
                .eq(OutboxPO::getEventId, id)
                .eq(OutboxPO::getLeaseToken, lease)
                .eq(OutboxPO::getDeliveryState, "CLAIMED")
                .set(OutboxPO::getDeliveryState, "PENDING")
                .set(OutboxPO::getLastErrorCode, "DEPENDENCY_UNAVAILABLE")
                .set(OutboxPO::getLeaseToken, null)
                .set(OutboxPO::getLeasedUntil, null)
                .setSql("available_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 5 SECOND)"));
    }

    /**
     * 检查该事件是否已有指定消费者的完成记录。
     */
    @Select("SELECT EXISTS(SELECT 1 FROM sys_event_receipt WHERE event_id=#{id} AND consumer_code=#{consumer})")
    boolean hasReceipt(@Param("id") String id, @Param("consumer") String consumer);

    /**
     * 确认当前消费者已完成该事件，重复投递按事件与消费者防重。
     */
    @Insert(
            "INSERT INTO sys_event_receipt(event_id,consumer_code,completed_at) VALUES(#{id},#{consumer},UTC_TIMESTAMP(3))")
    int receipt(@Param("id") String id, @Param("consumer") String consumer);
}
