package com.dataocean.module.governance.entity.dto;

/**
 * 用固定资料的段落或范围定位实际引用。
 *
 * @param section 来源单元，正文使用 BODY，附件使用解析返回的位置
 * @param start 正文中的起始字符偏移，包含该位置
 * @param end 正文中的结束字符偏移，不包含该位置
 */
public record EvidenceLocationDTO(
        String section,
        Integer start,
        Integer end) {}
