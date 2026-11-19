package com.dataocean.module.model.entity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 模型内容保存，编辑时留空凭据沿用当次凭据修订。
 *
 * @param name 管理人员看到的配置名称
 * @param protocol 生成接口格式
 * @param endpoint 已准入服务的 SDK 基址
 * @param modelName 提供方接受的模型名称
 * @param timeoutSeconds 单次调用超时上限，单位秒
 * @param credential 新增或明确替换时提供的秘密
 * @param version 编辑时预期的模型版本
 */
public record ModelSaveDTO(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Pattern(regexp = "OPENAI_CHAT_COMPLETIONS|ANTHROPIC_MESSAGES") String protocol,
        @NotBlank @Size(max = 500) String endpoint,
        @NotBlank @Size(max = 120) String modelName,
        @NotNull @Min(1) @Max(120) Integer timeoutSeconds,
        @Size(max = 2048) String credential,
        Integer version) {

    /**
     * 调试输出只暴露配置身份和版本。
     */
    @Override
    public String toString() {
        return "ModelSaveDTO[protocol=" + protocol + ", version=" + version + "]";
    }
}
