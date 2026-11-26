package com.dataocean.module.feedback.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 已在源系统生效的数据修正记录，供具备来源授权的人员复验。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceDataResultDTO {

    /**
     * 保存时所见的事项版本。
     */
    @Positive
    private int expectedVersion;

    /**
     * 实际操作源系统的人员或团队，区别于平台跟进人。
     */
    @NotBlank
    @Size(max = 120)
    private String repairer;

    /**
     * 实际修正的数据范围和内容。
     */
    @NotBlank
    @Size(max = 1000)
    private String summary;

    /**
     * 处理人只读复查所用方式与实际结果。
     */
    @NotBlank
    @Size(max = 1000)
    private String checkMethod;

    /**
     * 可关联的工单、批次或其他既有记录。
     */
    @Size(max = 500)
    private String reference;

    /**
     * 确认修正和必要的同步已生效。
     */
    private boolean effectiveConfirmed;
}
