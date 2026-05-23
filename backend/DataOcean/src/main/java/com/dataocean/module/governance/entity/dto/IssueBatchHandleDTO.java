package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 质量问题批量处理请求参数。
 */
@Data
public class IssueBatchHandleDTO {

    @NotEmpty(message = "问题ID列表不能为空")
    @Size(max = 100, message = "单次批量操作不能超过100条")
    private List<Long> issueIds;

    @NotNull(message = "目标状态不能为空")
    private String status;
}
