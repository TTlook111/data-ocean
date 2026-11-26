package com.dataocean.module.datasource.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.datasource.entity.dto.ChangeReviewDTO;
import com.dataocean.module.datasource.entity.dto.ChangeReviewPreviewDTO;
import com.dataocean.module.datasource.entity.query.StructureChangeQuery;
import com.dataocean.module.datasource.entity.vo.StructureChangeVO;
import com.dataocean.module.datasource.service.impl.StructureReviewService;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.security.RequirePermission;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 读取和核实来源结构变更。
 */
@RestController
@RequestMapping("/api/v1/datasources/{sourceId}/changes")
@RequiredArgsConstructor
public class StructureChangeController {

    /**
     * 实际结构事实的读取和核实业务。
     */
    private final StructureReviewService changes;

    /**
     * 分页列出明确来源的变化。
     */
    @GetMapping
    @RequirePermission("collections.view")
    public Result<PageVO<StructureChangeVO>> list(@PathVariable String sourceId, StructureChangeQuery query) {
        return Result.success(changes.list(Ids.parse(sourceId), query));
    }

    /**
     * 回看实际快照和事实证据。
     */
    @GetMapping("/{changeId}")
    @RequirePermission("collections.view")
    public Result<StructureChangeVO> detail(@PathVariable String sourceId, @PathVariable String changeId) {
        return Result.success(changes.detail(Ids.parse(sourceId), Ids.parse(changeId)));
    }

    /**
     * 核对选择的结论和依据。
     */
    @PostMapping("/{changeId}/impact-preview")
    @RequirePermission("collections.review")
    public Result<ImpactPreviewVO> preview(
            @PathVariable String sourceId,
            @PathVariable String changeId,
            @Valid @RequestBody ChangeReviewPreviewDTO input) {
        return Result.success(changes.preview(Ids.parse(sourceId), Ids.parse(changeId), input));
    }

    /**
     * 实际追加人工核实并更新目录投影。
     */
    @PostMapping("/{changeId}/reviews")
    @RequirePermission("collections.review")
    public Result<StructureChangeVO> review(
            @PathVariable String sourceId,
            @PathVariable String changeId,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody ChangeReviewDTO input) {
        return Result.success(changes.review(Ids.parse(sourceId), Ids.parse(changeId), command, input));
    }
}
