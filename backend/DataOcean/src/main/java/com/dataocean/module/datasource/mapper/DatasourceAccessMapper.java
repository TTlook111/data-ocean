package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.DatasourceAccess;
import com.dataocean.module.datasource.entity.vo.DatasourceAccessVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DatasourceAccessMapper extends BaseMapper<DatasourceAccess> {

    @Select("""
            SELECT a.id,
                   a.datasource_id AS datasourceId,
                   a.user_id AS userId,
                   u.username,
                   u.real_name AS realName,
                   a.granted_by AS grantedBy,
                   a.granted_at AS grantedAt,
                   a.expires_at AS expiresAt
            FROM datasource_access a
            LEFT JOIN sys_user u ON u.id = a.user_id
            WHERE a.datasource_id = #{datasourceId}
            ORDER BY a.granted_at DESC, a.id DESC
            """)
    List<DatasourceAccessVO> selectAccessList(@Param("datasourceId") Long datasourceId);

    @Select("""
            SELECT COUNT(*)
            FROM datasource_access a
            JOIN datasource d ON d.id = a.datasource_id
            WHERE a.datasource_id = #{datasourceId}
              AND a.user_id = #{userId}
              AND d.status = 1
              AND d.deleted = 0
              AND (a.expires_at IS NULL OR a.expires_at > NOW())
            """)
    Long countEnabledAccess(@Param("datasourceId") Long datasourceId, @Param("userId") Long userId);
}
