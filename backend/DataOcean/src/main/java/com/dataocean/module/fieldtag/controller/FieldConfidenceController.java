package com.dataocean.module.fieldtag.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.fieldtag.entity.dto.ConfidenceUpdateRequestDTO;
import com.dataocean.module.fieldtag.entity.vo.ConfidenceEventVO;
import com.dataocean.module.fieldtag.entity.vo.ConfidenceVO;
import com.dataocean.module.fieldtag.service.FieldConfidenceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 字段可信度控制器
 * <p>
 * 提供字段可信度的查询、管理员设置和变更历史 API。
 * </p>
 */
@RestController
@RequestMapping("/api/field-confidence")
@RequiredArgsConstructor
@Slf4j
public class FieldConfidenceController {

    private final FieldConfidenceService fieldConfidenceService;

    /**
     * 查询单个字段的可信度
     *
     * @param columnMetaId 字段元数据ID
     * @return 可信度信息
     */
    @GetMapping("/{columnMetaId}")
    public Result<ConfidenceVO> getConfidence(@PathVariable Long columnMetaId) {
        return Result.success(fieldConfidenceService.getConfidence(columnMetaId));
    }

    /**
     * 批量查询字段可信度
     *
     * @param columnMetaIds 字段元数据ID列表（逗号分隔）
     * @return 可信度列表
     */
    @GetMapping("/batch")
    public Result<List<ConfidenceVO>> batchGetConfidence(@RequestParam List<Long> columnMetaIds) {
        return Result.success(fieldConfidenceService.batchGetConfidence(columnMetaIds));
    }

    /**
     * 管理员手动设置字段可信度
     *
     * @param columnMetaId 字段元数据ID
     * @param request      设置请求参数
     * @return 设置后的可信度信息
     */
    @PutMapping("/{columnMetaId}")
    @PreAuthorize("hasAuthority('field-tag:manage')")
    public Result<ConfidenceVO> adminSetScore(@PathVariable Long columnMetaId,
                                              @Valid @RequestBody ConfidenceUpdateRequestDTO request) {
        return Result.success("设置成功", fieldConfidenceService.adminSetScore(
                columnMetaId, request.getScore(), request.getReason()));
    }

    /**
     * 查询字段可信度变更历史
     *
     * @param columnMetaId 字段元数据ID
     * @return 变更事件列表
     */
    @GetMapping("/{columnMetaId}/events")
    public Result<List<ConfidenceEventVO>> getEventHistory(@PathVariable Long columnMetaId) {
        return Result.success(fieldConfidenceService.getEventHistory(columnMetaId));
    }
}
