package com.dataocean.module.iam.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 服务内部使用的安全散列与凭据修订，不作为接口入参或返回。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
@TableName("iam_user_credential")
public final class CredentialPO {

    /**
     * 当前人员的稳定标识，客户端自报值不能替代登录身份。
     */
    @TableId(type = IdType.INPUT)
    private Long userId;

    /**
     * 框架编码的密码散列，只留在受控凭据存储。
     */
    @ToString.Exclude
    private String passwordHash;

    /**
     * 凭据修订，防止验密期间重置后使用旧校验结果。
     */
    private int credentialRevision;

    /**
     * 该凭据或快照变化实际发生的 UTC 时刻。
     */
    private LocalDateTime changedAt;
}
