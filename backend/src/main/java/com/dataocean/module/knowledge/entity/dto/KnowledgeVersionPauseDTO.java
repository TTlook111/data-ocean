package com.dataocean.module.knowledge.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 对已核实的正式绑定发起暂停。
 *
 * @param reason 暂停本次使用范围的实际原因
 */
public record KnowledgeVersionPauseDTO(@NotBlank @Size(max = 300) String reason) {}
