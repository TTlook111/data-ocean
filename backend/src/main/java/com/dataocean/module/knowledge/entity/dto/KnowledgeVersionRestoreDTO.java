package com.dataocean.module.knowledge.entity.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 显式恢复时确认本次审阅的隔离结果及风险消除依据。
 *
 * @param runId 本人回看并核对过的当前恢复验证运行
 * @param reason 原风险已经消除的依据
 */
public record KnowledgeVersionRestoreDTO(@NotNull UUID runId, @NotBlank @Size(max = 300) String reason) {}
