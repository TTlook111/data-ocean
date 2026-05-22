package com.dataocean.module.knowledge.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 生成 AI 草稿请求参数
 */
@Data
public class GenerateDraftDTO {

    /** 元数据快照 ID */
    @NotNull(message = "快照 ID 不能为空")
    private Long snapshotId;
}
