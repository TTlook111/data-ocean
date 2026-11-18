package com.dataocean.module.datasource.entity.vo;

import com.dataocean.module.datasource.entity.vo.CatalogPageVO.ObjectRow;
import com.dataocean.module.datasource.source.SourceMetadata.Field;
import com.dataocean.module.datasource.source.SourceMetadata.Key;

import java.util.List;

/**
 * 逐对象描述实际成功采集的结构，展示字段、键和适用索引。
 *
 * @param object 目录对象身份及来源
 * @param objectRevision 对应此快照的结构修订
 * @param columns 字段定义
 * @param primaryKeys 主键成员
 * @param foreignKeys 来源声明的外键成员
 * @param indexes 源端适用的索引
 * @param parent 分区父对象路径
 */
public record CatalogObjectDetailVO(
        ObjectRow object,
        String objectRevision,
        List<Field> columns,
        List<Key> primaryKeys,
        List<Key> foreignKeys,
        List<Key> indexes,
        String parent) {}
