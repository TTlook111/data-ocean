package com.dataocean.module.access.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 同一申请内可追溯的独立办理子项。
 */
@Data
@TableName("access_request_item")
public class AccessRequestItemPO {

    /**
     * 子项稳定编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属申请编号。
     */
    private Long requestId;

    /**
     * 当前核定源，未知时为空。
     */
    private Long sourceId;

    /**
     * 当前实际办理状态。
     */
    private String state;

    /**
     * 办理时采用的规则修订和正文快照。
     */
    private String ruleSnapshot;

    /**
     * 最近实际办理人编号。
     */
    private Long handledBy;

    /**
     * 最近实际办理的 UTC 时间。
     */
    private LocalDateTime handledAt;

    /**
     * 最近办理说明。
     */
    private String reason;

    /**
     * 本子项单独重提后的业务用途。
     */
    private String applicationPurpose;

    /**
     * 本子项单独重提后的数据需求。
     */
    private String applicationDataNeeded;

    /**
     * 本子项独立重提的补充说明。
     */
    private String applicationSupplement;
}
