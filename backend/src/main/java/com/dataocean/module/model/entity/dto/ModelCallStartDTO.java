package com.dataocean.module.model.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import lombok.Data;

/**
 * 受认证执行服务在实际外发前登记固定节点和配置快照。
 */
@Data
public class ModelCallStartDTO {

    /**
     * 发出模型请求的经营流程分类。
     */
    @NotBlank
    @Pattern(regexp = "GOVERNANCE|QUERY|KNOWLEDGE|FEEDBACK")
    private String module;

    /**
     * 仅用于诊断归属和历史去重的原任务标识，不包含业务资料。
     */
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9_-]{1,100}")
    private String originId;

    /**
     * 本次实际调用在模型目录中的节点编码。
     */
    @NotBlank
    @Pattern(regexp = "[a-z][a-z0-9-]{0,39}")
    private String nodeCode;

    /**
     * 模型配置修订的十进制标识，按字符串传输避免精度损失。
     */
    @NotBlank
    @Pattern(regexp = "[0-9]{1,19}")
    private String revisionId;
}
