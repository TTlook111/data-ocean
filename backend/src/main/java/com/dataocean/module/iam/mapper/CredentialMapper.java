package com.dataocean.module.iam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.iam.entity.po.CredentialPO;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 访问独立存储的账号凭据，仅供认证业务使用。
 */
public interface CredentialMapper extends BaseMapper<CredentialPO> {

    /**
     * 只向受控凭据表保存已编码的散列，时间与数据库事务一致。
     */
    @Insert(
            "INSERT INTO iam_user_credential(user_id,password_hash,changed_at) VALUES(#{user},#{hash},UTC_TIMESTAMP(3))")
    int create(@Param("user") long user, @Param("hash") String hash);

    /**
     * 写入新散列并原子递增凭据修订。
     */
    default int password(long user, String hash) {
        return update(Wrappers.<CredentialPO>lambdaUpdate()
                .eq(CredentialPO::getUserId, user)
                .set(CredentialPO::getPasswordHash, hash)
                .setIncrBy(CredentialPO::getCredentialRevision, 1)
                .setSql("changed_at=UTC_TIMESTAMP(3)"));
    }
}
