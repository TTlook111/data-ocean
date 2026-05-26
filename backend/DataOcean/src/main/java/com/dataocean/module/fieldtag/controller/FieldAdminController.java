package com.dataocean.module.fieldtag.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.fieldtag.entity.vo.ConfidenceTrendPointVO;
import com.dataocean.module.fieldtag.service.ConfidenceTrendService;
import com.dataocean.module.fieldtag.service.FieldTagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 字段管理管理端控制器
 * <p>
 * 提供可信度趋势、CSV 导入标签、自动打标等管理功能。
 * 需要 field-tag:manage 权限。
 * </p>
 */
@RestController
@RequestMapping("/api/admin/fields")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('field-tag:manage')")
@Slf4j
public class FieldAdminController {

    private final ConfidenceTrendService confidenceTrendService;
    private final FieldTagService fieldTagService;

    /**
     * 查询字段可信度趋势数据
     *
     * @param fieldId 字段元数据ID
     * @param days    查询天数（默认 30）
     * @return 趋势数据点列表
     */
    @GetMapping("/{fieldId}/confidence-trend")
    public Result<List<ConfidenceTrendPointVO>> getConfidenceTrend(
            @PathVariable Long fieldId,
            @RequestParam(defaultValue = "30") int days) {
        return Result.success(confidenceTrendService.getTrend(fieldId, days));
    }

    /**
     * CSV 批量导入字段标签
     * <p>
     * CSV 格式：column_id,tag_code（每行一条记录）
     * </p>
     *
     * @param file CSV 文件
     * @return 导入结果（成功数/失败数）
     */
    @PostMapping("/import-tags")
    public Result<Map<String, Integer>> importTags(@RequestParam("file") MultipartFile file) {
        Map<String, Integer> result = confidenceTrendService.importTagsFromCsv(file);
        return Result.success("导入完成", result);
    }

    /**
     * 根据字段名模式匹配自动打标
     * <p>
     * 规则：*_amount → 金额类, *_time/*_date → 时间类, *_status → 状态类
     * </p>
     *
     * @param datasourceId 数据源ID（限定范围）
     * @return 自动打标结果
     */
    @PostMapping("/auto-tag")
    public Result<Map<String, Integer>> autoTag(@RequestParam Long datasourceId) {
        Map<String, Integer> result = confidenceTrendService.autoTagByPattern(datasourceId);
        return Result.success("自动打标完成", result);
    }
}
