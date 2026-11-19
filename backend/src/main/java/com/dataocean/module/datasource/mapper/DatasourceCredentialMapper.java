package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.po.DatasourceCredentialPO;
import com.dataocean.module.datasource.mapper.DatasourceMapper.CredentialRow;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    /**
     * 按同源配置修订读取已绑定凭据，防止凭据跨来源匹配。
     */
    @Select(
            """
            SELECT c.key_version,c.ciphertext FROM ds_connection_revision r
            JOIN ds_credential_revision c ON c.id=r.credential_revision_id AND c.source_id=r.source_id
            WHERE r.source_id=#{source} AND r.id=#{revision} AND OCTET_LENGTH(c.ciphertext)>0
            """)
    CredentialRow forConfiguration(@Param("source") long source, @Param("revision") long revision);

    /**
     * 退役且停止确认后解除密文，保留历史快照关联要求的修订身份。
     */
    @Update("UPDATE ds_credential_revision SET ciphertext=X'' WHERE source_id=#{source}")
    int eraseRetired(long source);
}
