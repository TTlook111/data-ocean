package com.dataocean.module.access.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 申请聚合根，固定目标身份并控制单据版本。
 */
@Data
@TableName("access_request")
public class AccessRequestPO {

    /**
     * 申请稳定编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 实际提交人及访问目标。
     */
    private Long applicantId;

    /**
     * 需要完成的业务工作。
     */
    private String purpose;

    /**
     * 需要使用的数据说明。
     */
    private String dataNeeded;

    /**
     * 最近一次补充说明。
     */
    private String supplement;

    /**
     * 全部子项必须一起授予时为真。
     */
    private Boolean joint;

    /**
     * 影响申请整体的变化修订。
     */
    private Integer version;

    /**
     * 首次实际提交的 UTC 时间。
     */
    private LocalDateTime submittedAt;
}
