package com.dataocean.module.datasource.entity.po;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;
import lombok.ToString;

/**
 * 映射 ds_credential_revision 的持久化字段。
 */
@Data
@TableName("ds_credential_revision")
public class DatasourceCredentialPO {

    /**
     * 数据库生成的凭据修订编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 凭据所属数据源。
     */
    private Long sourceId;

    /**
     * 凭据所用加密密钥版本。
     */
    private String keyVersion;

    /**
     * 加密后的凭据正文。
     */
    @ToString.Exclude
    private byte[] ciphertext;

    /**
     * 提交凭据的操作者。
     */
    private Long createdBy;

    /**
     * 凭据写入时的数据库 UTC 时间。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
