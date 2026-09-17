package com.dataocean.module.permission.s1.mapper;

import com.dataocean.module.permission.s1.entity.IamS1DatasourceFact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * S1 仅用于确认数据源资源身份和状态的专用 Mapper，不提供业务数据授权。
 */
@Mapper
public interface IamS1DatasourceIdentityMapper {

    @Select("""
            SELECT id, name, status, deleted
            FROM datasource
            WHERE id = #{datasourceId}
              AND deleted = 0
            """)
    IamS1DatasourceFact selectIdentity(@Param("datasourceId") Long datasourceId);

    @Select("""
            SELECT COUNT(*)
            FROM datasource
            WHERE id = #{datasourceId}
              AND status = 1
              AND deleted = 0
            """)
    long countEnabledDatasource(@Param("datasourceId") Long datasourceId);
}
