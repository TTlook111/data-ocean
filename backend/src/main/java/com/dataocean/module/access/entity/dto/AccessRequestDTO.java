package com.dataocean.module.access.entity.dto;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.Data;

/**
 * 本人填写的申请内容，规则由审批人选择。
 */
@Data
public class AccessRequestDTO {

    /**
     * 编辑或补充时读取的申请版本。
     */
    @Min(1)
    private Integer expectedVersion;

    /**
     * 申请要完成的业务工作。
     */
    @NotBlank
    @CodePointLength(max = 500)
    private String purpose;

    /**
     * 业务人员描述的所需数据。
     */
    @NotBlank
    @CodePointLength(max = 2000)
    private String dataNeeded;

    /**
     * 补充重提的说明。
     */
    @CodePointLength(max = 1000)
    private String supplement;

    /**
     * 已知数据源列表，可为空。
     */
    @Size(max = 30)
    private List<@NotBlank String> sourceIds;

    /**
     * 保存草稿时为真。
     */
    @NotNull
    private Boolean draft;
}
