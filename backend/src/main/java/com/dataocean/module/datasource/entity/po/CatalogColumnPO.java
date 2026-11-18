package com.dataocean.module.datasource.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 固定目录字段身份及其归属对象。
 */
@Data
@TableName("ds_column")
public class CatalogColumnPO {

    /**
     * 数据库生成的字段身份。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 字段所属目录对象身份。
     */
    private Long objectId;
}
