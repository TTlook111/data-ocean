package com.dataocean.module.glossary.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.glossary.entity.query.TermFieldQuery;
import com.dataocean.module.glossary.entity.vo.TermColumnVO;
import com.dataocean.module.glossary.mapper.GlossaryFieldMapper;
import com.dataocean.module.glossary.service.GlossaryFieldService;
import com.dataocean.module.metadata.entity.po.*;
import com.dataocean.module.metadata.mapper.*;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GlossaryFieldServiceImpl implements GlossaryFieldService {
    private final MetadataEntityMapper entityMapper;
    private final MetadataSnapshotMapper snapshotMapper;
    private final DbColumnMetaMapper columnMapper;
    private final DbTableMetaMapper tableMapper;
    private final DatasourceMapper datasourceMapper;
    private final GlossaryFieldMapper fieldMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<java.util.Map<String, Object>> sources(java.util.Set<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return datasourceMapper.selectList(new LambdaQueryWrapper<com.dataocean.module.datasource.entity.po.Datasource>()
                .in(com.dataocean.module.datasource.entity.po.Datasource::getId, ids)
                .eq(com.dataocean.module.datasource.entity.po.Datasource::getStatus, 1)
                .orderByAsc(com.dataocean.module.datasource.entity.po.Datasource::getName))
                .stream().map(d -> java.util.Map.<String, Object>of("id", d.getId(), "name", d.getName())).toList();
    }

    private MetadataSnapshot published(Long datasourceId) {
        var snapshot = snapshotMapper.selectOne(new LambdaQueryWrapper<MetadataSnapshot>()
                .eq(MetadataSnapshot::getDatasourceId, datasourceId)
                .eq(MetadataSnapshot::getStatus, "PUBLISHED").orderByDesc(MetadataSnapshot::getId).last("LIMIT 1"));
        if (snapshot == null) throw new BusinessException(409, "该数据源尚无已发布快照");
        return snapshot;
    }

    @Override
    public List<DbTableMeta> tables(Long datasourceId) {
        var snapshot = published(datasourceId);
        return tableMapper.selectList(new LambdaQueryWrapper<DbTableMeta>()
                .eq(DbTableMeta::getDatasourceId, datasourceId).eq(DbTableMeta::getSnapshotId, snapshot.getId())
                .notIn(DbTableMeta::getGovernanceStatus, List.of("BLOCKED", "DEPRECATED"))
                .orderByAsc(DbTableMeta::getTableName));
    }

    @Override
    public IPage<TermColumnVO> columns(TermFieldQuery query) {
        if (query.getDatasourceId() == null || query.getTableName() == null || query.getTableName().isBlank())
            throw new BusinessException(400, "请选择数据源和表");
        var snapshot = published(query.getDatasourceId());
        return fieldMapper.columns(query.toMpPage(), query.getDatasourceId(), snapshot.getId(),
                query.getTableName(), query.getKeyword());
    }

    @Override
    public TermColumnVO resolve(Long entityId) {
        var entity = entityId == null ? null : entityMapper.selectById(entityId);
        if (entity == null || !"COLUMN".equals(entity.getEntityType()))
            throw new BusinessException(409, "关联字段已失效或不是物理字段");
        try {
            var metadata = objectMapper.readTree(entity.getEntityMetadata());
            long sourceId = metadata.path("datasource_id").asLong();
            var source = datasourceMapper.selectById(sourceId);
            if (source == null || !Integer.valueOf(1).equals(source.getStatus()))
                throw new BusinessException(409, "关联数据源不存在或已停用");
            var snapshot = published(sourceId);
            if (metadata.path("snapshot_id").asLong() != snapshot.getId())
                throw new BusinessException(409, "关联字段不属于当前已发布快照，请重新选择");
            var columns = columnMapper.selectList(new LambdaQueryWrapper<DbColumnMeta>()
                    .eq(DbColumnMeta::getDatasourceId, sourceId).eq(DbColumnMeta::getSnapshotId, snapshot.getId())
                    .eq(DbColumnMeta::getColumnName, entity.getName()));
            for (var column : columns) {
                String fqn = MetadataEntity.fqnColumn(source.getName(), source.getDatabaseName(),
                        column.getTableName(), column.getColumnName());
                if (!fqn.equalsIgnoreCase(entity.getFqn())) continue;
                var table = tableMapper.selectById(column.getTableMetaId());
                if (table == null || !snapshot.getId().equals(table.getSnapshotId())
                        || !Long.valueOf(sourceId).equals(table.getDatasourceId())
                        || List.of("BLOCKED", "DEPRECATED").contains(column.getGovernanceStatus())
                        || List.of("BLOCKED", "DEPRECATED").contains(table.getGovernanceStatus()))
                    throw new BusinessException(409, "字段或所属表已阻断、废弃或来源不完整");
                return new TermColumnVO(entityId, sourceId, source.getName(), snapshot.getId(), table.getId(),
                        column.getId(), table.getTableName(), column.getColumnName(), column.getDataType(),
                        column.getColumnComment(), fqn);
            }
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw new BusinessException(409, "字段来源无法核验，请重新选择"); }
        throw new BusinessException(409, "该字段已不在当前已发布快照中");
    }
}
