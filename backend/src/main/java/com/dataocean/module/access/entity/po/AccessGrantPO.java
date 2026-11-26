package com.dataocean.module.access.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 审批形成的个人来源及独立撤销事实。
 */
@Data
@TableName("access_grant")
public class AccessGrantPO {

    /**
     * 个人来源编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 唯一关联的批准子项。
     */
    private Long itemId;

    /**
     * 来源实际开始时间，UTC。
     */
    private LocalDateTime startedAt;

    /**
     * 来源结束时间，未撤销时为空。
     */
    private LocalDateTime endedAt;

    /**
     * 实际撤销操作人编号。
     */
    private Long endedBy;

    /**
     * 结束原因，保存历史。
     */
    private String endReason;
}
