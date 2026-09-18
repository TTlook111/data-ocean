package com.dataocean.module.permission.s1.mapper;

import com.dataocean.module.permission.s1.entity.IamS1ColumnOptionFact;
import com.dataocean.module.permission.s1.entity.IamS1SnapshotOption;
import com.dataocean.module.permission.s1.entity.IamS1TableOptionFact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * S1 授权与字段保护表单所需的资源事实读取。
 * <p>
 * 只读取已发布元数据快照、表与字段资源事实，不读取旧数据授权、旧策略或业务原始记录。
 * </p>
 */
@Mapper
public interface IamS1ResourceOptionMapper {

    @Select("""
            SELECT id, datasource_id, snapshot_version, status
            FROM metadata_snapshot
            WHERE datasource_id = #{datasourceId}
              AND status = 'PUBLISHED'
            ORDER BY snapshot_version DESC, id DESC
            """)
    List<IamS1SnapshotOption> selectPublishedSnapshots(@Param("datasourceId") Long datasourceId);

    @Select("""
            SELECT id, snapshot_id, datasource_id, table_name, table_comment, governance_status
            FROM db_table_meta
            WHERE snapshot_id = #{snapshotId}
              AND datasource_id = #{datasourceId}
            ORDER BY table_name
            """)
    List<IamS1TableOptionFact> selectTables(@Param("datasourceId") Long datasourceId,
                                            @Param("snapshotId") Long snapshotId);

    @Select("""
            SELECT id, snapshot_id, table_meta_id, datasource_id, table_name,
                   column_name, column_comment, data_type, governance_status
            FROM db_column_meta
            WHERE snapshot_id = #{snapshotId}
              AND datasource_id = #{datasourceId}
              AND table_name = #{tableName}
            ORDER BY ordinal_position, id
            """)
    List<IamS1ColumnOptionFact> selectColumns(@Param("datasourceId") Long datasourceId,
                                              @Param("snapshotId") Long snapshotId,
                                              @Param("tableName") String tableName);

    @Select("""
            SELECT COUNT(*)
            FROM metadata_snapshot
            WHERE id = #{snapshotId}
              AND datasource_id = #{datasourceId}
              AND status = 'PUBLISHED'
            """)
    long countPublishedSnapshot(@Param("datasourceId") Long datasourceId,
                                @Param("snapshotId") Long snapshotId);

    /** 申请入口可选的数据源：已启用且至少存在一个已发布快照；只返回名称，不暴露连接或业务记录。 */
    @Select("""
            SELECT DISTINCT ds.id AS id, ds.name AS name
            FROM datasource ds
            JOIN metadata_snapshot ms ON ms.datasource_id = ds.id AND ms.status = 'PUBLISHED'
            WHERE ds.status = 1
              AND ds.deleted = 0
            ORDER BY ds.id
            """)
    List<java.util.Map<String, Object>> selectEnabledDatasourcesWithPublishedSnapshot();
}
