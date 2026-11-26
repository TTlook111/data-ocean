package com.dataocean.module.governance.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用固定资料的段落或范围定位实际引用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceLocationDTO {

    /**
     * 来源单元，正文使用 BODY，附件使用解析返回的位置。
     */
    private String section;

    /**
     * 正文中的起始字符偏移，包含该位置。
     */
    private Integer start;

    /**
     * 正文中的结束字符偏移，不包含该位置。
     */
    private Integer end;
}
