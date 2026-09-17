package com.dataocean.module.permission.s1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.permission.s1.entity.IamS1DataGrant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** IAM-SIMPLE-1 数据授权主事实 Mapper。 */
@Mapper
public interface IamS1DataGrantMapper extends BaseMapper<IamS1DataGrant> {

    @Select("""
            SELECT id, protocol_version, subject_type, subject_id, department_scope,
                   datasource_id, resource_scope, metadata_snapshot_id, table_name,
                   effect, grant_source, source_reference_id, valid_from, valid_until,
                   status, revision_no, created_by, updated_by, created_at, updated_at
            FROM iam_s1_data_grant
            WHERE protocol_version = #{protocolVersion}
              AND datasource_id = #{datasourceId}
              AND status = 'ACTIVE'
            ORDER BY id
            """)
    List<IamS1DataGrant> selectActiveByDatasource(@Param("protocolVersion") String protocolVersion,
                                                  @Param("datasourceId") Long datasourceId);
}
