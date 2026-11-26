package com.dataocean.module.governance.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 稳定身份及当前治理定义与确认的指针。
 */
@Data
@TableName("gov_item")
public class GovernanceItemPO {

    /**
     * 治理条目的数据库主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 条目归属的稳定数据源。
     */
    private Long sourceId;

    /**
     * 当前表和字段所属的稳定源对象。
     */
    private Long objectId;

    /**
     * 对象的来源世代。
     */
    private Integer generation;

    /**
     * 条目类型 TABLE 或 COLUMN。
     */
    private String kind;

    /**
     * 对应的实际对象或字段编号。
     */
    private Long targetId;

    /**
     * 工作台写操作的并发版本。
     */
    private Integer version;

    /**
     * 最新业务内容修订，草稿也可以指到。
     */
    private Long headRevisionId;

    /**
     * 最后一次实际确认的内容修订。
     */
    private Long lastConfirmedRevisionId;

    /**
     * 条目物化的 UTC 毫秒时刻。
     */
    private LocalDateTime createdAt;

    /**
     * 条目实际状态变更的 UTC 毫秒时刻。
     */
    private LocalDateTime updatedAt;
}
