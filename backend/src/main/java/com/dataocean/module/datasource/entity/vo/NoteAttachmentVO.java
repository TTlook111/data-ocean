package com.dataocean.module.datasource.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 列表只披露附件名称及大小，下载单独核验。
 */
@Data
@AllArgsConstructor
public class NoteAttachmentVO {

    /**
     * 附件标识。
     */
    private String id;

    /**
     * 原文件名称。
     */
    private String name;

    /**
     * 文件实际字节数。
     */
    private int byteSize;
}
