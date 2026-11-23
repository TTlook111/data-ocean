package com.dataocean.module.datasource.entity.vo;

import com.dataocean.module.datasource.entity.vo.CatalogPageVO.ObjectRow;
import com.dataocean.module.datasource.source.SourceMetadata.Field;
import com.dataocean.module.datasource.source.SourceMetadata.Key;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 逐对象描述实际成功采集的结构，展示字段、键和适用索引。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CatalogObjectDetailVO {

    /**
     * 目录对象身份及来源。
     */
    private ObjectRow object;

    /**
     * 对应此快照的结构修订。
     */
    private String objectRevision;

    /**
     * 字段定义。
     */
    private List<Field> columns;

    /**
     * 主键成员。
     */
    private List<Key> primaryKeys;

    /**
     * 来源声明的外键成员。
     */
    private List<Key> foreignKeys;

    /**
     * 源端适用的索引。
     */
    private List<Key> indexes;

    /**
     * 分区父对象路径。
     */
    private String parent;
}
