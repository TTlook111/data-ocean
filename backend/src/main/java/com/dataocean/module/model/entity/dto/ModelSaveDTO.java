package com.dataocean.module.model.entity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 模型内容保存，编辑时留空凭据沿用当次凭据修订。
 */
@Data
public class ModelSaveDTO {

    /**
     * 管理人员看到的配置名称。
     */
    @NotBlank
    @Size(max = 100)
    private String name;

    /**
     * 生成接口格式。
     */
    @NotBlank
    @Pattern(regexp = "OPENAI_CHAT_COMPLETIONS|ANTHROPIC_MESSAGES")
    private String protocol;

    /**
     * 已准入服务的 SDK 基址。
     */
    @NotBlank
    @Size(max = 500)
    private String endpoint;

    /**
     * 提供方接受的模型名称。
     */
    @NotBlank
    @Size(max = 120)
    private String modelName;

    /**
     * 单次调用超时上限，单位秒。
     */
    @NotNull
    @Min(1)
    @Max(120)
    private Integer timeoutSeconds;

    /**
     * 新增或明确替换时提供的秘密。
     */
    @Size(max = 2048)
    private String credential;

    /**
     * 编辑时预期的模型版本。
     */
    private Integer version;

    /**
     * 调试输出只暴露配置身份和版本。
     */
    @Override
    public String toString() {
        return "ModelSaveDTO[protocol=" + protocol + ", version=" + version + "]";
    }
}
