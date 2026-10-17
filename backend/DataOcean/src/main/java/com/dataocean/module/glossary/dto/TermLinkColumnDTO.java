package com.dataocean.module.glossary.dto;

import lombok.Data;

/**
 * 术语关联列请求 DTO。
 */
@Data
public class TermLinkColumnDTO {
    private Long revision;

    /** 元数据实体 ID */
    private Long entityId;
}
