package com.dataocean.module.access.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 共享输出限制规则的当前修订。
 */
@Data
@TableName("access_output_rule")
public class OutputRulePO {

    /**
     * 规则稳定编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 规则名称。
     */
    private String name;

    /**
     * 用于当前授权计算的中文限制全文。
     */
    private String content;

    /**
     * 共享规则修订。
     */
    private Integer version;

    /**
     * 数据库记录的最近修改时间，UTC。
     */
    private LocalDateTime updatedAt;
}
