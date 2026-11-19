package com.dataocean.module.datasource.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.ToString;

/**
 * 保存不可变的已验证原文件内容。
 */
@Data
@TableName("ds_business_attachment")
public class NoteAttachmentPO {

    /**
     * 原文件稳定编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 原始文件限定归属的资料编号。
     */
    private Long noteId;

    /**
     * 安全的原文件名。
     */
    private String name;

    /**
     * 文件实际字节数。
     */
    private Integer byteSize;

    /**
     * 内容的 SHA-256 校验值。
     */
    private String digest;

    /**
     * 仅在经过权限校验的下载动作或写入时读取。
     */
    @TableField(select = false)
    @ToString.Exclude
    private byte[] content;
}
