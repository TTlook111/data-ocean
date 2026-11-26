package com.dataocean.module.datasource.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * ds_object 单表持久化数据。
 */
@Data
@TableName("ds_object")
public class CatalogObjectPO {

    /**
     * 数据库生成的稳定对象身份。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 对象的来源范围。
     */
    private Long sourceId;

    /**
     * 限定路径重建后对象身份的世代。
     */
    private Integer generation;

    /**
     * 用于关联同源对象的身份摘要。
     */
    private byte[] sourceIdentityKey;

    /**
     * 证实重建时关联的前任身份。
     */
    private Long predecessorObjectId;

    /**
     * 确认退役事实所对应的变化记录。
     */
    private Long retiredChangeId;

    /**
     * 确认旧身份退役的数据库时间。
     */
    private LocalDateTime retiredAt;
}
