package com.dataocean.module.governance.entity.dto;

/**
 * 保留编码与业务含义的精确对应。
 *
 * @param code 原编码，保留前导零
 * @param meaning 编码的业务解释
 */
public record CodeMeaningDTO(
        String code,
        String meaning) {}
