package com.dataocean.module.fieldtag.service;

import com.dataocean.module.fieldtag.entity.vo.ConfidenceTrendPointVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 可信度趋势与批量操作服务接口
 * <p>
 * 提供可信度趋势查询、CSV 导入标签、自动打标等管理功能。
 * </p>
 */
public interface ConfidenceTrendService {

    /**
     * 查询字段可信度趋势数据
     *
     * @param columnMetaId 字段元数据ID
     * @param days         查询天数
     * @return 趋势数据点列表（按时间正序）
     */
    List<ConfidenceTrendPointVO> getTrend(Long columnMetaId, int days);

    /**
     * 从 CSV 文件批量导入字段标签
     * <p>
     * CSV 格式：column_id,tag_code（首行可为表头，自动跳过）
     * </p>
     *
     * @param file CSV 文件
     * @return 导入结果：success（成功数）、failed（失败数）
     */
    Map<String, Integer> importTagsFromCsv(MultipartFile file);

    /**
     * 根据字段名模式匹配自动打标
     * <p>
     * 规则：
     * - *_amount/*_price/*_fee → AMOUNT（金额类）
     * - *_time/*_date/*_at → TIME（时间类）
     * - *_status/*_state → STATUS（状态类）
     * </p>
     *
     * @param datasourceId 数据源ID（限定范围）
     * @return 打标结果：tagged（成功数）、skipped（跳过数）
     */
    Map<String, Integer> autoTagByPattern(Long datasourceId);
}
