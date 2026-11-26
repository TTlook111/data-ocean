package com.dataocean.module.datasource.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 按源留存企业说明的当前状态与内容指针。
 */
@Data
@TableName("ds_business_note")
public class BusinessNotePO {

    /**
     * 资料稳定编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 资料所属的稳定数据源。
     */
    private Long sourceId;

    /**
     * 首次提交人的真实账号。
     */
    private Long authorId;

    /**
     * 当前用于列表查询的资料标题。
     */
    private String title;

    /**
     * 当前可供治理使用、撤下或已删除。
     */
    private String state;

    /**
     * 并发核对使用的聚合版本。
     */
    private Integer version;

    /**
     * 实质内容修订序号。
     */
    private Integer revision;

    /**
     * 最近一次撤下原因，恢复时保留。
     */
    private String stateReason;

    /**
     * 资料最近变更的 UTC 时间。
     */
    private LocalDateTime updatedAt;
}
