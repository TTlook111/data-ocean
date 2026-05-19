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
}
