package com.dataocean.module.governance.entity.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

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

    /** 每个选中问题固定各自原版本；服务端要求与去重后的问题 ID 完整对应。 */
    @NotEmpty(message = "批量问题预期版本不能为空")
    private Map<@NotNull @Positive Long, @NotNull @Positive Long> expectedVersions;
}
