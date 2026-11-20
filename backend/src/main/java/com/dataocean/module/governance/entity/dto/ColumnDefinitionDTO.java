package com.dataocean.module.governance.entity.dto;

import java.util.List;

import lombok.Data;

/**
 * 明确一个字段的详细业务含义与使用约束。
 */
@Data
public class ColumnDefinitionDTO {

    /**
     * 字段的业务名称。
     */
    private String businessName;

    /**
     * 字段表达的实际业务含义。
     */
    private String meaning;

    /**
     * 值域及解释。
     */
    private String valueDescription;

    /**
     * 原编码与业务含义的逐项对应，最多 200 行，保留编码前导零。
     */
    private List<CodeMeaningDTO> valueMappings;

    /**
     * 金额与数量的业务单位。
     */
    private String unit;

    /**
     * 读取或展示字段值时遵循的业务格式。
     */
    private String format;

    /**
     * 空值的业务含义。
     */
    private String nullMeaning;

    /**
     * 空字符串的业务含义。
     */
    private String emptyMeaning;

    /**
     * 零值的业务含义。
     */
    private String zeroMeaning;

    /**
     * 适用的业务时间含义。
     */
    private String timeSemantics;

    /**
     * 用于解释业务时间的 IANA 时区标识或 UTC 偏移。
     */
    private String businessTimeZone;

    /**
     * 适用的筛选及计算口径。
     */
    private String usageRule;

    /**
     * 使用注意事项。
     */
    private String usageNotes;

    /**
     * 敏感性及合法使用的业务说明。
     */
    private String sensitivityNotes;
}
