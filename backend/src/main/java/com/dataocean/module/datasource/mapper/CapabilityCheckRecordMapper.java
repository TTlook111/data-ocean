package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.po.CapabilityCheckPO;
import com.dataocean.module.datasource.mapper.DatasourceMapper.CheckRow;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 访问 ds_capability_check 的单表处理状态。
 */
public interface CapabilityCheckRecordMapper extends BaseMapper<CapabilityCheckPO> {

    /**
     * 绑定平台生成的报告和摘要，使用数据库时钟生成五分钟效期。
     */
    @Insert(
            """
            INSERT INTO ds_capability_check (source_id,actor_id,base_version,connection_fingerprint,
                credential_fingerprint,fingerprint_key_version,adapter_version,policy_version,detected_version,
                verified_scope,coverage,results,read_only_safety,business_read_status,checked_at,expires_at)
            VALUES (#{row.sourceId},#{actor},#{version},#{connection},#{credential},'1',#{adapterVersion},
                #{policyVersion},#{detectedVersion},#{verified},#{coverage},#{results},#{safety},#{business},
                UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)+INTERVAL 5 MINUTE)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int create(
            @Param("row") CapabilityCheckPO row,
            @Param("actor") long actor,
            @Param("version") Integer version,
            @Param("connection") byte[] connection,
            @Param("credential") byte[] credential,
            @Param("adapterVersion") String adapterVersion,
            @Param("policyVersion") String policyVersion,
            @Param("detectedVersion") String detectedVersion,
            @Param("verified") String verified,
            @Param("coverage") String coverage,
            @Param("results") String results,
            @Param("safety") String safety,
            @Param("business") String business);

    /**
     * 按数据库时钟判断报告效期，并读取应用核对所需的绑定证据。
     */
    @Select(
            """
            SELECT id,actor_id,source_id,base_version,connection_fingerprint AS configuration_fingerprint,
                credential_fingerprint,adapter_version,policy_version,
                DATE_FORMAT(checked_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS created,
                DATE_FORMAT(expires_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS expires,
                expires_at>UTC_TIMESTAMP(3) AS valid,results AS report
            FROM ds_capability_check WHERE id=#{id}
            """)
    CheckRow evidence(@Param("id") long id);
}
