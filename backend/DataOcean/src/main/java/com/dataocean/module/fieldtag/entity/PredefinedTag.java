package com.dataocean.module.fieldtag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 预定义标签实体类（已废弃）
 * <p>
 * 已被 {@code classification} + {@code tag} 两级标签体系替代。
 * 保留原表用于历史数据兼容，新标签应写入 tag 表。
 * </p>
 *
 * @deprecated 使用 classification + tag 替代
 */
@Deprecated
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
