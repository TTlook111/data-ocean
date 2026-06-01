package com.dataocean.module.knowledge.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 批量生成 skills.md 请求 DTO。
 * <p>
 * 用户选择一个元数据快照，AI 自动分析业务域并批量生成多份 skills.md。
 * </p>
 */
@Data
public class BatchGenerateDTO {

    /** 元数据快照 ID */
    @NotNull(message = "快照ID不能为空")
    private Long snapshotId;
}
