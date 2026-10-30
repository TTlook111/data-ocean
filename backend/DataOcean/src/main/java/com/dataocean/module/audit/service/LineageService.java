package com.dataocean.module.audit.service;

import com.dataocean.module.audit.entity.vo.LineageTableVO;
import com.dataocean.module.audit.entity.vo.LineageColumnVO;
import com.dataocean.module.audit.entity.vo.ImpactAnalysisVO;

import java.util.List;

/**
 * 血缘服务接口
 * <p>
 * 提供 SQL 血缘关系的存储和查询功能。
 * 血缘数据来源于 Python sqlglot 的解析结果。
 * </p>
 */
public interface LineageService {

    /** 从受保护尝试保存查询使用/结果来源索引，不产生物理表之间的边。 */
    void saveProtectedLineage(com.dataocean.module.query.entity.po.QueryTask task,
                              java.util.List<java.util.Map<String, Object>> sourceTrace);

    /**
     * 按表名查询血缘关系
     *
     * @param tableName 表名
     * @return 引用该表的历史查询列表
     */
    List<LineageTableVO> queryTableLineage(Long datasourceId, String tableName);

    /**
     * 按字段查询血缘关系
     *
     * @param tableName  表名
     * @param columnName 字段名
     * @return 引用该字段的历史查询列表
     */
    List<LineageColumnVO> queryColumnLineage(Long datasourceId, String tableName, String columnName);

    /**
     * 变更影响分析
     *
     * @param tableName  表名
     * @param columnName 字段名
     * @return 依赖该字段的查询数量和影响范围
     */
    ImpactAnalysisVO analyzeImpact(Long datasourceId, String tableName, String columnName);
}
