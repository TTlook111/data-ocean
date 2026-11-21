package com.dataocean.module.knowledge.entity.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 每个成员明确指出已读取的准备任务和版本。
 */
@Data
public class KnowledgeBatchSelectionDTO {

    /**
     * 至少选择两个待发布任务，单份继续使用原入口。
     */
    @NotEmpty
    @Size(min = 2, max = 20)
    private List<@NotNull @Valid Member> members;

    /**
     * @param taskId 本次明确纳入的单文档准备任务
     * @param expectedVersion 上次读到的任务版本
     */
    public record Member(@NotBlank String taskId, @NotNull @Positive Integer expectedVersion) {}
}
