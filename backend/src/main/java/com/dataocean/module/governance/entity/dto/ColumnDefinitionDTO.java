package com.dataocean.module.governance.entity.dto;

import java.util.List;

/**
 * 明确一个字段的详细业务含义与使用约束。
 *
 * @param businessName 字段的业务名称
 * @param meaning 字段表达的实际业务含义
 * @param valueDescription 值域及解释
 * @param valueMappings 适用的编码规则
 * @param unit 金额与数量的业务单位
 * @param format 字段值的业务格式
 * @param nullMeaning 空值的业务含义
 * @param emptyMeaning 空字符串的业务含义
 * @param zeroMeaning 零值的业务含义
 * @param timeSemantics 适用的业务时间含义
 * @param businessTimeZone 适用的业务时区
 * @param usageRule 适用的筛选及计算口径
 * @param usageNotes 使用注意事项
 * @param sensitivityNotes 敏感性及合法使用的业务说明
 */
public record ColumnDefinitionDTO(
        String businessName,
        String meaning,
        String valueDescription,
        List<CodeMeaningDTO> valueMappings,
        String unit,
        String format,
        String nullMeaning,
        String emptyMeaning,
        String zeroMeaning,
        String timeSemantics,
        String businessTimeZone,
        String usageRule,
        String usageNotes,
        String sensitivityNotes) {}
