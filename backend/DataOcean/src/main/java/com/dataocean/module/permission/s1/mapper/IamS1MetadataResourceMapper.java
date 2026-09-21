package com.dataocean.module.permission.s1.mapper;

import com.dataocean.module.permission.s1.entity.IamS1ColumnFact;
import com.dataocean.module.permission.s1.entity.IamS1MetadataSnapshotFact;
import com.dataocean.module.permission.s1.entity.IamS1TableFact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** S1 只读取已发布元数据和治理准入事实。 */
@Mapper
public interface IamS1MetadataResourceMapper {

    @Select("""
            SELECT id, datasource_id, status
            FROM metadata_snapshot
            WHERE id = #{snapshotId}
              AND datasource_id = #{datasourceId}
            """)
    IamS1MetadataSnapshotFact selectSnapshot(@Param("snapshotId") Long snapshotId,
                                             @Param("datasourceId") Long datasourceId);

    @Select("""
            SELECT id, snapshot_id, datasource_id, table_name, governance_status
            FROM db_table_meta
            WHERE snapshot_id = #{snapshotId}
              AND datasource_id = #{datasourceId}
              AND table_name = #{tableName}
            """)
    IamS1TableFact selectTable(@Param("snapshotId") Long snapshotId,
                               @Param("datasourceId") Long datasourceId,
                               @Param("tableName") String tableName);

    @Select("""
            SELECT id, snapshot_id, table_meta_id, datasource_id, table_name,
                   column_name, data_type, governance_status
            FROM db_column_meta
            WHERE snapshot_id = #{snapshotId}
              AND datasource_id = #{datasourceId}
              AND table_name = #{tableName}
            ORDER BY ordinal_position, id
            """)
    List<IamS1ColumnFact> selectColumns(@Param("snapshotId") Long snapshotId,
                                        @Param("datasourceId") Long datasourceId,
                                        @Param("tableName") String tableName);
}
