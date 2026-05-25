package com.dataocean.module.knowledge.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.DatasourceSecret;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceSecretMapper;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构建 skills.md 版本依赖快照，记录该版本内容生成时依赖的连接、快照和可信度来源。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeDependencySnapshotBuilder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final MetadataSnapshotMapper metadataSnapshotMapper;
    private final DatasourceMapper datasourceMapper;
    private final DatasourceSecretMapper datasourceSecretMapper;

    public String build(Long datasourceId, Long snapshotId, String generationSource) {
        return build(datasourceId, snapshotId, generationSource, Map.of());
    }

    public String build(Long datasourceId, Long snapshotId, String generationSource, Map<String, Object> extra) {
        Map<String, Object> dependency = new LinkedHashMap<>();
        dependency.put("schemaVersion", 1);
        dependency.put("generationSource", generationSource);
        dependency.put("datasource", datasourceDependency(datasourceId));
        dependency.put("metadataSnapshot", metadataSnapshotDependency(snapshotId));
        dependency.put("confidenceSources", List.of(
                "db_column_meta.confidence_score",
                "metadata_snapshot.quality_score"));
        if (extra != null && !extra.isEmpty()) {
            dependency.put("extra", extra);
        }
        return toJson(dependency);
    }

    private Map<String, Object> datasourceDependency(Long datasourceId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", datasourceId);
        if (datasourceId == null) {
            return data;
        }

        Datasource datasource = datasourceMapper.selectById(datasourceId);
        if (datasource != null) {
            data.put("dbType", datasource.getDbType());
            data.put("host", datasource.getHost());
            data.put("port", datasource.getPort());
            data.put("databaseName", datasource.getDatabaseName());
            data.put("charset", datasource.getCharset());
            data.put("configUpdatedAt", formatTime(datasource.getUpdatedAt()));
        }

        DatasourceSecret secret = datasourceSecretMapper.selectOne(
                new LambdaQueryWrapper<DatasourceSecret>()
                        .eq(DatasourceSecret::getDatasourceId, datasourceId));
        if (secret != null) {
            data.put("credential", Map.of(
                    "id", secret.getId(),
                    "encryptVersion", secret.getEncryptVersion(),
                    "updatedAt", formatTime(secret.getUpdatedAt())));
        }
        return data;
    }

    private Map<String, Object> metadataSnapshotDependency(Long snapshotId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", snapshotId);
        if (snapshotId == null) {
            return data;
        }

        MetadataSnapshot snapshot = metadataSnapshotMapper.selectById(snapshotId);
        if (snapshot != null) {
            data.put("snapshotVersion", snapshot.getSnapshotVersion());
            data.put("schemaHash", snapshot.getSchemaHash());
            data.put("status", snapshot.getStatus());
            data.put("qualityScore", snapshot.getQualityScore());
            data.put("createdAt", formatTime(snapshot.getCreatedAt()));
            data.put("updatedAt", formatTime(snapshot.getUpdatedAt()));
        }
        return data;
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? null : time.toString();
    }

    private String toJson(Map<String, Object> dependency) {
        try {
            return OBJECT_MAPPER.writeValueAsString(dependency);
        } catch (Exception e) {
            throw new BusinessException("文档依赖快照生成失败");
        }
    }
}
