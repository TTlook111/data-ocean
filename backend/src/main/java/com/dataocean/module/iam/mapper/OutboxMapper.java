package com.dataocean.module.iam.mapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.iam.entity.po.OutboxPO;


public interface OutboxMapper {
    @Insert("INSERT INTO sys_outbox(event_id,event_type,aggregate_type,aggregate_id,aggregate_version,command_id,payload,available_at,occurred_at) VALUES(#{d.eventId},#{d.eventType},#{d.aggregateType},#{d.aggregateId},#{d.aggregateVersion},#{d.commandId},#{d.payload},UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))") int insert(@Param("d") OutboxPO data);
    @Select("SELECT * FROM sys_outbox WHERE (delivery_state='PENDING' AND available_at<=UTC_TIMESTAMP(3)) OR (delivery_state='CLAIMED' AND leased_until<UTC_TIMESTAMP(3)) ORDER BY occurred_at,event_id LIMIT 10 FOR UPDATE SKIP LOCKED") List<OutboxPO> pending();
    @Update("UPDATE sys_outbox SET delivery_state='CLAIMED',lease_token=#{lease},leased_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND),attempts=attempts+1 WHERE event_id=#{id}") int claim(@Param("id") String id,@Param("lease") String lease);
    @Update("UPDATE sys_outbox SET delivery_state='DELIVERED',delivered_at=UTC_TIMESTAMP(3),lease_token=NULL,leased_until=NULL WHERE event_id=#{id} AND lease_token=#{lease} AND delivery_state='CLAIMED'") int delivered(@Param("id") String id,@Param("lease") String lease);
    @Update("UPDATE sys_outbox SET delivery_state='PENDING',available_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 5 SECOND),last_error_code='DEPENDENCY_UNAVAILABLE',lease_token=NULL,leased_until=NULL WHERE event_id=#{id} AND lease_token=#{lease} AND delivery_state='CLAIMED'") int retry(@Param("id") String id,@Param("lease") String lease);
    @Select("SELECT COUNT(*) FROM sys_event_receipt WHERE event_id=#{id} AND consumer_code=#{consumer}") int received(@Param("id") String id,@Param("consumer") String consumer);
    @Insert("INSERT INTO sys_event_receipt(event_id,consumer_code,completed_at) VALUES(#{id},#{consumer},UTC_TIMESTAMP(3))") int receipt(@Param("id") String id,@Param("consumer") String consumer);
}
