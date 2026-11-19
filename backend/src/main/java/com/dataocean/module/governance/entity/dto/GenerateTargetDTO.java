package com.dataocean.module.governance.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 本阶段的表说明生成目标和固定内容范围。
 *
 * @param kind 首段支持 TABLE
 * @param targetId 被选择的稳定对象
 * @param precondition 实际预览的并发和结构版本
 * @param contentPaths 拟生成的业务字段，空值代表整项
 */
public record GenerateTargetDTO(
        @NotBlank String kind,
        @NotBlank String targetId,
        @Valid @NotNull EntryPreconditionDTO precondition,
        List<@NotBlank String> contentPaths) {}
