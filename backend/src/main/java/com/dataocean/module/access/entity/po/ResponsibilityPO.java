package com.dataocean.module.access.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 同一数据源当前生效的两位审批责任人。
 */
@Data
@TableName("access_responsibility")
public class ResponsibilityPO {

    /**
     * 与数据源相同的稳定主键。
     */
    @TableId(type = IdType.INPUT)
    private Long sourceId;

    /**
     * 主审批人编号。
     */
    private Long primaryUserId;

    /**
     * 替代负责人编号。
     */
    private Long alternateUserId;

    /**
     * 责任配置修订。
     */
    private Integer version;

    /**
     * 最近配置时间，UTC。
     */
    private LocalDateTime updatedAt;
}
