package com.dataocean.module.fieldtag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 预定义标签实体类
 * <p>
 * 系统预定义的标签参考数据，用于前端下拉选择和标签编码校验。
 * </p>
 */
@Data
@TableName("predefined_tag")
public class PredefinedTag {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标签编码 */
    private String tagCode;

    /** 标签显示名称 */
    private String tagName;

    /** 标签分类 */
    private String category;

    /** 标签说明 */
    private String description;

    /** 排序序号 */
    private Integer sortOrder;
}
