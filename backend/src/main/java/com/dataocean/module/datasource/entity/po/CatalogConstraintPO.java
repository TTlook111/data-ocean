package com.dataocean.module.datasource.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 对象修订内已经确认的约束或索引。
 */
@Data
@TableName("ds_constraint")
public class CatalogConstraintPO {

    /**
     * 数据库生成的约束编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 本约束归属的对象修订。
     */
    private Long objectRevisionId;

    /**
     * 主键、外键、索引或唯一索引。
     */
    private String kind;

    /**
     * 来源数据库中的约束名称。
     */
    private String sourceName;

    /**
     * 跨对象约束的 JSON 来源路径。
     */
    private String targetObjectRef;
}
