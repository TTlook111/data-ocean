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

    /**
     * 保存血缘数据
     * <p>
     * 接收 Python 返回的 used_tables 和 used_columns 解析结果，
     * 批量写入 query_lineage_table 和 query_lineage_column。
     * </p>
     *
     * @param queryTaskId 查询任务ID
     * @param usedTables  使用的表 JSON 字符串
     * @param usedColumns 使用的字段 JSON 字符串
     */
    void saveLineage(Long queryTaskId, String usedTables, String usedColumns);

    /**
     * 按表名查询血缘关系
     *
     * @param tableName 表名
     * @return 引用该表的历史查询列表
     */
    List<LineageTableVO> queryTableLineage(String tableName);

    /**
     * 按字段查询血缘关系
     *
     * @param tableName  表名
     * @param columnName 字段名
     * @return 引用该字段的历史查询列表
     */
    List<LineageColumnVO> queryColumnLineage(String tableName, String columnName);

    /**
     * 变更影响分析
     *
     * @param tableName  表名
     * @param columnName 字段名
     * @return 依赖该字段的查询数量和影响范围
     */
    ImpactAnalysisVO analyzeImpact(String tableName, String columnName);
}
