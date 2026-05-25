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

    /**
     * 构建依赖快照 JSON（无额外信息）。
     *
     * @param datasourceId     数据源 ID
     * @param snapshotId       元数据快照 ID，可为 null
     * @param generationSource 生成来源（AI_GENERATED / MANUAL / ROLLBACK）
     * @return JSON 字符串
     */
    public String build(Long datasourceId, Long snapshotId, String generationSource) {
        return build(datasourceId, snapshotId, generationSource, Map.of());
    }

    /**
     * 构建依赖快照 JSON，包含数据源连接摘要、元数据快照版本和可信度来源。
     *
     * @param datasourceId     数据源 ID
     * @param snapshotId       元数据快照 ID，可为 null
     * @param generationSource 生成来源（AI_GENERATED / MANUAL / ROLLBACK）
     * @param extra            额外附加信息（如 AI 生成时的 warnings）
     * @return JSON 字符串
     */
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

    /**
     * 构建数据源连接依赖摘要（不含密码和用户名等敏感信息）。
     */
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

    /**
     * 构建元数据快照依赖摘要。
     */
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

    /**
     * 格式化时间为 ISO 字符串，null 安全。
     */
    private String formatTime(LocalDateTime time) {
        return time == null ? null : time.toString();
    }

    /**
     * 将依赖 Map 序列化为 JSON 字符串。
     */
    private String toJson(Map<String, Object> dependency) {
        try {
            return OBJECT_MAPPER.writeValueAsString(dependency);
        } catch (Exception e) {
            throw new BusinessException("文档依赖快照生成失败");
        }
    }
}
