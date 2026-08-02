package com.dataocean.module.audit.service;

import com.dataocean.module.audit.entity.dto.LineageCreateRequest;
import com.dataocean.module.audit.entity.vo.LineageEdgeVO;
import com.dataocean.module.audit.entity.vo.LineageGraphVO;

import java.util.List;
import java.util.Set;

/**
 * 血缘边管理服务接口
 * <p>
 * 提供 ETL/MANUAL 血缘的手动创建、删除、批量导入和增强查询功能。
 * 归属 com.dataocean.module.audit 包，与现有 LineageService 同 module。
 * </p>
 *
 * @author dataocean
 */
public interface LineageEdgeService {

    /**
     * 创建 LINEAGE 关系
     * <p>
     * 单条创建表级血缘（TABLE → TABLE），自动根据 columnMappings 生成对应 DERIVED_FROM 边（COLUMN → COLUMN）。
     * 校验源/目标实体类型均为 TABLE，拒绝直接创建 COLUMN → COLUMN 的 LINEAGE。
     * </p>
     *
     * @param request 血缘创建请求
     * @return 创建结果
     */
    LineageEdgeVO createLineage(LineageCreateRequest request);

    /**
     * 删除 LINEAGE 关系
     * <p>
     * cascadeDerived=true 时级联删除关联的 DERIVED_FROM 边；
     * cascadeDerived=false 或未传时仅删除 LINEAGE 边，DERIVED_FROM 保留为孤边。
     * 删除前返回关联的 DERIVED_FROM 边数量供前端弹窗提示。
     * </p>
     *
     * @param relationshipId 关系主键 ID
     * @param cascadeDerived 是否级联删除关联的 DERIVED_FROM 边
     * @return 关联的 DERIVED_FROM 边数量
     */
    int deleteLineage(Long relationshipId, boolean cascadeDerived);

    /**
     * 批量创建血缘（支持 CSV/JSON 文件导入）
     *
     * @param requests 批量创建请求列表
     * @return 创建结果列表
     */
    List<LineageEdgeVO> batchCreateLineage(List<LineageCreateRequest> requests);

    /**
     * 查询带列映射增强的血缘图谱
     * <p>
     * 在原始 getLineage 基础上，增加节点信息、列映射摘要和血缘类型过滤。
     * 支持按 lineageType 过滤（逗号分隔）。
     * </p>
     *
     * @param entityId    实体 ID
     * @param depth       图谱深度（BFS 遍历层数）
     * @param lineageTypes 血缘类型过滤集合（QUERY/ETL/MANUAL）
     * @return 增强血缘图谱
     */
    LineageGraphVO getEnrichedLineage(Long entityId, int depth, Set<String> lineageTypes);
}
