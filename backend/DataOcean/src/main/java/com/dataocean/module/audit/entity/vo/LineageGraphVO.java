package com.dataocean.module.audit.entity.vo;

import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 增强血缘图谱响应 VO
 * <p>
 * 在原始 getLineage 返回的关系列表基础上，增加节点信息和列映射摘要，
 * 使前端不需要再次请求实体详情即可渲染完整图谱。
 * </p>
 *
 * @author dataocean
 */
@Data
public class LineageGraphVO {

    /** 图谱中的节点（实体）列表 */
    private List<MetadataEntity> nodes;

    /** 图谱中的关系边列表 */
    private List<MetadataRelationship> edges;

    /**
     * 列映射摘要
     * <p>
     * Map 的 key 为 relationshipId，value 为该 LINEAGE 边关联的列映射摘要列表。
     * 摘要来自 relation_metadata.column_mappings，仅用于图谱展示，
     * 完整列级血缘请走 GET .../column-lineage。
     * </p>
     */
    private Map<Long, List<Map<String, Object>>> columnMappingsByEdge;

    /** 图谱深度 */
    private int depth;
}
