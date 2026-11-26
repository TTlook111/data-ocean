package com.dataocean.module.datasource.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 用于绑定能力检查报告与实际数据源。
 */
@Data
@TableName("ds_capability_check")
public class CapabilityCheckPO {

    /**
     * 数据库生成的能力检查编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 已绑定的来源编号，新源创建前为空。
     */
    private Long sourceId;
}
