package com.dataocean.module.datasource.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 保存已验证原件的不可变元数据和 OSS 定位。
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
     * 原件写入的私有 Bucket，供读取和清理核对环境。
     */
    private String objectBucket;

    /**
     * 原件在 Bucket 内的完整对象键，每次新增分配独立值。
     */
    private String objectKey;
}
