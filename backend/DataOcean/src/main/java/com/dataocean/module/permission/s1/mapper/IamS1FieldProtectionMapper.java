package com.dataocean.module.permission.s1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.permission.s1.entity.IamS1FieldProtection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** IAM-SIMPLE-1 字段保护 Mapper。 */
@Mapper
public interface IamS1FieldProtectionMapper extends BaseMapper<IamS1FieldProtection> {

    @Select("""
            SELECT id, protocol_version, datasource_id, metadata_snapshot_id, table_name,
                   column_meta_id, column_name, protection_level, mask_policy, status,
                   revision_no, created_by, updated_by, created_at, updated_at
            FROM iam_s1_field_protection
            WHERE protocol_version = #{protocolVersion}
              AND datasource_id = #{datasourceId}
              AND metadata_snapshot_id = #{snapshotId}
              AND status = 'ACTIVE'
            ORDER BY column_meta_id, id
            """)
    List<IamS1FieldProtection> selectActiveBySnapshot(@Param("protocolVersion") String protocolVersion,
                                                      @Param("datasourceId") Long datasourceId,
                                                      @Param("snapshotId") Long snapshotId);
}
