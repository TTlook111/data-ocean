package com.dataocean.module.knowledge.entity.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 显式恢复时确认本次审阅的隔离结果及风险消除依据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeVersionRestoreDTO {

    /**
     * 本人回看并核对过的当前恢复验证运行。
     */
    @NotNull
    private UUID runId;

    /**
     * 原风险已经消除的依据。
     */
    @NotBlank
    @Size(max = 300)
    private String reason;
}
