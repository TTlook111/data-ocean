package com.dataocean.module.governance.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 按稳定条目维护权威使用限制及追加式原因。
 */
public interface GovernanceControlMapper {

    /**
     * 对象级限制覆盖本表的所有字段，字段级限制只匹配当前字段。
     */
    @Select(
            """
            SELECT EXISTS(SELECT 1 FROM gov_usage_control c JOIN gov_item i ON i.id=c.item_id
                          WHERE i.source_id=#{source} AND i.object_id=#{object} AND c.blocked
                            AND (i.kind='TABLE' OR (i.kind=#{kind} AND i.target_id=#{target})))
            """)
    boolean blocked(
            @Param("source") long source,
            @Param("object") long object,
            @Param("kind") String kind,
            @Param("target") long target);

    /**
     * 来源防重与当前状态分开记录，允许多个独立风险保留其各自证据。
     */
    @Select(
            "SELECT EXISTS(SELECT 1 FROM gov_usage_control_event WHERE item_id=#{item} AND structure_change_id=#{change})")
    boolean recorded(@Param("item") long item, @Param("change") long change);

    /**
     * 锁源后推进该对象限制版本。
     */
    @Insert(
            """
            INSERT INTO gov_usage_control(item_id,blocked,version) VALUES(#{item},TRUE,1)
            ON DUPLICATE KEY UPDATE blocked=TRUE,version=version+1,updated_at=UTC_TIMESTAMP(3)
            """)
    int forbid(long item);

    /**
     * 读取刚登记的权威限制修订。
     */
    @Select("SELECT version FROM gov_usage_control WHERE item_id=#{item}")
    int version(long item);

    /**
     * 同事务追加本次使用控制的审计原因。
     */
    @Insert(
            """
            INSERT INTO gov_usage_control_event
                 (item_id,control_version,action,reason_code,reason,structure_change_id,command_id,actor_kind,actor_id)
            VALUES(#{item},#{version},'FORBID',#{code},#{reason},#{change},#{command},
                   IF(#{actor} IS NULL,'SYSTEM','USER'),#{actor})
            """)
    int event(
            @Param("item") long item,
            @Param("version") int version,
            @Param("code") String code,
            @Param("reason") String reason,
            @Param("change") Long change,
            @Param("command") String command,
            @Param("actor") Long actor);
}
