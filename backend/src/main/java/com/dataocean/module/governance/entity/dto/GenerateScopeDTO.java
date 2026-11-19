package com.dataocean.module.governance.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 预览和提交共用同一明确范围。
 *
 * @param targets 首段实际支持的单张表说明
 * @param reviewToken 正式提交使用服务端核对凭据
 */
public record GenerateScopeDTO(
        @NotNull @Size(min = 1, max = 1) List<@Valid GenerateTargetDTO> targets,
        String reviewToken) {}
