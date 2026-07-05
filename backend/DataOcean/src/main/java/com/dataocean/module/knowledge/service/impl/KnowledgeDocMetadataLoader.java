package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.fieldtag.entity.FieldTag;
import com.dataocean.module.fieldtag.mapper.FieldTagMapper;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.entity.TableRelation;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识文档元数据加载服务
 * <p>
 * 负责从元数据表加载表、字段、外键、标签等信息，
 * 用于 skills.md 生成和文档发布时的依赖快照构建。
 * </p>
 *
 * @author DataOcean
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocMetadataLoader {

    private final DbTableMetaMapper dbTableMetaMapper;
    private final DbColumnMetaMapper dbColumnMetaMapper;
    private final TableRelationMapper tableRelationMapper;
    private final FieldTagMapper fieldTagMapper;

    /**
     * 加载指定数据源的表元数据
     *
     * @param datasourceId 数据源 ID
     * @return 表元数据列表
     */
    public List<DbTableMeta> loadTables(Long datasourceId) {
        return dbTableMetaMapper.selectList(
                new LambdaQueryWrapper<DbTableMeta>()
                        .eq(DbTableMeta::getDatasourceId, datasourceId)
        );
    }

    /**
     * 加载指定表的字段元数据
     *
     * @param tableIds 表 ID 列表
     * @return 字段元数据列表
     */
    public List<DbColumnMeta> loadColumns(List<Long> tableIds) {
        if (tableIds == null || tableIds.isEmpty()) {
            return new ArrayList<>();
        }
        return dbColumnMetaMapper.selectList(
                new LambdaQueryWrapper<DbColumnMeta>()
                        .in(DbColumnMeta::getTableMetaId, tableIds)
        );
    }

    /**
     * 加载指定数据源的外键关系
     *
     * @param datasourceId 数据源 ID
     * @return 外键关系列表
     */
    public List<TableRelation> loadForeignKeys(Long datasourceId) {
        return tableRelationMapper.selectList(
                new LambdaQueryWrapper<TableRelation>()
                        .eq(TableRelation::getDatasourceId, datasourceId)
        );
    }

    /**
     * 加载指定表的字段标签
     *
     * @param tableIds 表 ID 列表
     * @return 字段标签映射（key: columnId, value: 标签列表）
     */
    public Map<Long, List<FieldTag>> loadFieldTags(List<Long> tableIds) {
        if (tableIds == null || tableIds.isEmpty()) {
            return new HashMap<>();
        }
        List<FieldTag> tags = fieldTagMapper.selectList(
                new LambdaQueryWrapper<FieldTag>()
                        .in(FieldTag::getColumnMetaId, tableIds)
        );
        Map<Long, List<FieldTag>> tagMap = new HashMap<>();
        for (FieldTag tag : tags) {
            tagMap.computeIfAbsent(tag.getColumnMetaId(), k -> new ArrayList<>()).add(tag);
        }
        return tagMap;
    }

    /**
     * 按表名分组字段
     *
     * @param columns 字段列表
     * @return 按表名分组的字段映射
     */
    public Map<String, List<DbColumnMeta>> groupColumnsByTable(List<DbColumnMeta> columns) {
        Map<String, List<DbColumnMeta>> grouped = new HashMap<>();
        for (DbColumnMeta column : columns) {
            grouped.computeIfAbsent(column.getTableName(), k -> new ArrayList<>()).add(column);
        }
        return grouped;
    }
}
