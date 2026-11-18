package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.po.DatasourceCredentialPO;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

/**
 * 提供 DatasourceCredentialPO 对应单表的持久化操作。
 */
public interface DatasourceCredentialMapper extends BaseMapper<DatasourceCredentialPO> {

    /**
     * 使用数据库时钟写入不可变凭据，并通过 JDBC 生成键返回修订编号。
     */
    @Insert(
            """
            INSERT INTO ds_credential_revision (source_id,key_version,ciphertext,created_by,created_at)
            VALUES (#{sourceId},#{keyVersion},#{ciphertext},#{createdBy},UTC_TIMESTAMP(3))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int create(DatasourceCredentialPO credential);
}
