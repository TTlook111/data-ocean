package com.dataocean.module.permission.service.impl;

import com.dataocean.module.permission.entity.vo.PermissionContextVO;
import com.dataocean.module.permission.enums.MaskStrategy;
import com.dataocean.module.permission.service.DataMaskingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 数据脱敏服务实现
 * <p>
 * 支持 5 种脱敏策略：手机号、身份证、邮箱、银行卡、姓名。
 * </p>
 *
 * @author dataocean
 */
@Service
@Slf4j
public class DataMaskingServiceImpl implements DataMaskingService {

    @Override
    public List<Map<String, Object>> maskResult(List<Map<String, Object>> data,
                                                 List<PermissionContextVO.MaskColumnItem> maskColumns) {
        if (data == null || data.isEmpty() || maskColumns == null || maskColumns.isEmpty()) {
            return data;
        }

        // 构建列名→脱敏策略映射（忽略表名前缀，按列名匹配）
        Map<String, String> columnStrategyMap = new HashMap<>();
        for (PermissionContextVO.MaskColumnItem item : maskColumns) {
            columnStrategyMap.put(item.getColumnName().toLowerCase(), item.getMaskType());
        }

        // 对每行数据执行脱敏
        List<Map<String, Object>> maskedData = new ArrayList<>(data.size());
        for (Map<String, Object> row : data) {
            Map<String, Object> maskedRow = new LinkedHashMap<>(row);
            for (Map.Entry<String, Object> entry : maskedRow.entrySet()) {
                String strategy = columnStrategyMap.get(entry.getKey().toLowerCase());
                if (strategy != null && entry.getValue() != null) {
                    entry.setValue(maskValue(String.valueOf(entry.getValue()), strategy));
                }
            }
            maskedData.add(maskedRow);
        }
        return maskedData;
    }

    @Override
    public List<Map<String, Object>> maskResultByFields(List<Map<String, Object>> data,
                                                         Map<String, String> maskedFields) {
        if (data == null || data.isEmpty() || maskedFields == null || maskedFields.isEmpty()) {
            return data;
        }

        // maskedFields: {输出列名(小写) → 策略名}，直接匹配结果 key
        Map<String, String> normalizedMap = new HashMap<>();
        for (Map.Entry<String, String> entry : maskedFields.entrySet()) {
            normalizedMap.put(entry.getKey().toLowerCase(), entry.getValue());
        }

        List<Map<String, Object>> maskedData = new ArrayList<>(data.size());
        for (Map<String, Object> row : data) {
            Map<String, Object> maskedRow = new LinkedHashMap<>(row);
            for (Map.Entry<String, Object> entry : maskedRow.entrySet()) {
                String strategy = normalizedMap.get(entry.getKey().toLowerCase());
                if (strategy != null && entry.getValue() != null) {
                    entry.setValue(maskValue(String.valueOf(entry.getValue()), strategy));
                }
            }
            maskedData.add(maskedRow);
        }
        return maskedData;
    }

    @Override
    public String maskValue(String value, String strategy) {
        if (value == null || value.isBlank()) {
            return value;
        }

        try {
            MaskStrategy maskStrategy = MaskStrategy.valueOf(strategy);
            return switch (maskStrategy) {
                case PHONE -> maskPhone(value);
                case ID_CARD -> maskIdCard(value);
                case EMAIL -> maskEmail(value);
                case BANK_CARD -> maskBankCard(value);
                case NAME -> maskName(value);
            };
        } catch (IllegalArgumentException e) {
            log.warn("未知脱敏策略: {}", strategy);
            return value;
        }
    }

    /**
     * 手机号脱敏：138****5678（保留前3后4）
     */
    private String maskPhone(String value) {
        if (value.length() < 7) return mask(value, 1, 1);
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    /**
     * 身份证脱敏：3101**********1234（保留前4后4）
     */
    private String maskIdCard(String value) {
        if (value.length() < 8) return mask(value, 2, 2);
        return value.substring(0, 4) + "*".repeat(value.length() - 8) + value.substring(value.length() - 4);
    }

    /**
     * 邮箱脱敏：zha***@example.com（保留前3 + 域名）
     */
    private String maskEmail(String value) {
        int atIndex = value.indexOf('@');
        if (atIndex <= 0) return mask(value, 1, 0);
        String local = value.substring(0, atIndex);
        String domain = value.substring(atIndex);
        int keep = Math.min(3, local.length());
        return local.substring(0, keep) + "***" + domain;
    }

    /**
     * 银行卡脱敏：****5678（仅保留后4）
     */
    private String maskBankCard(String value) {
        if (value.length() <= 4) return "****";
        return "****" + value.substring(value.length() - 4);
    }

    /**
     * 姓名脱敏：张*（保留姓）
     */
    private String maskName(String value) {
        if (value.length() <= 1) return "*";
        return value.charAt(0) + "*".repeat(value.length() - 1);
    }

    /**
     * 通用脱敏：保留前 prefixKeep 和后 suffixKeep 位
     */
    private String mask(String value, int prefixKeep, int suffixKeep) {
        if (value.length() <= prefixKeep + suffixKeep) {
            return "*".repeat(value.length());
        }
        String prefix = value.substring(0, prefixKeep);
        String suffix = suffixKeep > 0 ? value.substring(value.length() - suffixKeep) : "";
        return prefix + "*".repeat(value.length() - prefixKeep - suffixKeep) + suffix;
    }
}
