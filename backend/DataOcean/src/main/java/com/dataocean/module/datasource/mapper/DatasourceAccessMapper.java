package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.DatasourceAccess;
import com.dataocean.module.datasource.entity.vo.DatasourceAccessVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 数据源访问授权 Mapper 接口
 * <p>
 * 提供对 datasource_access 表的基础 CRUD 操作，
 * 以及自定义的授权列表查询和有效授权计数方法。
 * </p>
 *
 * @author dataocean
 */
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
@Mapper
public interface DatasourceAccessMapper extends BaseMapper<DatasourceAccess> {

    /**
     * 查询指定数据源的授权用户列表
     * <p>
     * 关联 sys_user 表获取用户名和真实姓名，按授权时间倒序排列。
     * </p>
     *
     * @param datasourceId 数据源 ID
     * @return 授权用户视图对象列表
     */
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

    /**
     * 统计用户对指定数据源的有效授权数量
     * <p>
     * 有效授权需满足：数据源已启用且未删除，授权未过期。
     * 返回值大于 0 表示用户有权访问该数据源。
     * </p>
     *
     * @param datasourceId 数据源 ID
     * @param userId       用户 ID
     * @return 有效授权记录数
     */
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
