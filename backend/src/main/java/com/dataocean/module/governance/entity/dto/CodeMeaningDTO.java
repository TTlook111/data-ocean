package com.dataocean.module.governance.entity.dto;

import lombok.Data;

/**
 * 保留编码与业务含义的精确对应。
 */
@Data
public class CodeMeaningDTO {

    /**
     * 原编码，保留前导零。
     */
    private String code;

    /**
     * 编码的业务解释。
     */
    private String meaning;
}
