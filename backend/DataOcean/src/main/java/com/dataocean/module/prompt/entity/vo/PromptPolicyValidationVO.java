package com.dataocean.module.prompt.entity.vo;
/** 使用合成占位数据的只读预览，不调用模型。 */
public record PromptPolicyValidationVO(String systemPreview, String userPreview, String contractVersion) { }
