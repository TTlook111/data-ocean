package com.dataocean.module.permission.s1.mapper;

import com.dataocean.module.permission.s1.entity.IamS1ResponsibleDatasourceFact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * S1 能力摘要读取。只读 iam_s1_* 关系以及账号、数据源的资源身份事实。
 */
@Mapper
public interface IamS1CapabilityMapper {

    /** 用户在启用角色上被授予的全部功能码（不区分是否绑定负责源）。 */
    @Select("""
            SELECT DISTINCT f.function_code
            FROM iam_s1_user_role ur
            JOIN iam_s1_role r ON r.id = ur.role_id
            JOIN iam_s1_role_function rf ON rf.role_id = r.id
            JOIN iam_s1_function f ON f.id = rf.function_id
            WHERE ur.user_id = #{userId}
              AND ur.status = 1
              AND r.status = 1
              AND f.status = 'ACTIVE'
            ORDER BY f.function_code
            """)
    List<String> selectGrantedFunctionCodes(@Param("userId") Long userId);

    /**
     * 在某数据源上“功能与负责源同一绑定”同时成立的功能码。
     * 该查询与 {@code IamS1AuthorizationResolver#resolveAdminAction} 的同一绑定规则一致。
     */
    @Select("""
            SELECT DISTINCT f.function_code
            FROM iam_s1_user_role ur
            JOIN iam_s1_role r ON r.id = ur.role_id
            JOIN iam_s1_role_function rf ON rf.role_id = r.id
            JOIN iam_s1_function f ON f.id = rf.function_id
            JOIN iam_s1_role_datasource rd ON rd.user_role_id = ur.id
            WHERE ur.user_id = #{userId}
              AND ur.status = 1
              AND r.status = 1
              AND f.status = 'ACTIVE'
              AND rd.status = 1
              AND rd.datasource_id = #{datasourceId}
            ORDER BY f.function_code
            """)
    List<String> selectDatasourceFunctionCodes(@Param("userId") Long userId,
                                               @Param("datasourceId") Long datasourceId);

    /** 用户角色绑定负责的数据源（含绑定 ID，便于页面按绑定维护负责源）。 */
    @Select("""
            SELECT rd.user_role_id AS userRoleId, rd.datasource_id AS datasourceId,
                   d.name AS datasourceName, d.status AS datasourceStatus
            FROM iam_s1_user_role ur
            JOIN iam_s1_role r ON r.id = ur.role_id
            JOIN iam_s1_role_datasource rd ON rd.user_role_id = ur.id
            JOIN datasource d ON d.id = rd.datasource_id AND d.deleted = 0
            WHERE ur.user_id = #{userId}
              AND ur.status = 1
              AND r.status = 1
              AND rd.status = 1
            ORDER BY rd.id
            """)
    List<IamS1ResponsibleDatasourceFact> selectResponsibleDatasources(@Param("userId") Long userId);

    /** 单条用户角色绑定负责的数据源。 */
    @Select("""
            SELECT rd.user_role_id AS userRoleId, rd.datasource_id AS datasourceId,
                   d.name AS datasourceName, d.status AS datasourceStatus
            FROM iam_s1_role_datasource rd
            JOIN datasource d ON d.id = rd.datasource_id AND d.deleted = 0
            WHERE rd.user_role_id = #{userRoleId}
              AND rd.status = 1
            ORDER BY rd.id
            """)
    List<IamS1ResponsibleDatasourceFact> selectResponsibleDatasourcesByBinding(
            @Param("userRoleId") Long userRoleId);

    /** 全部启用数据源，仅用于系统管理员的受保护全范围判定。 */
    @Select("""
            SELECT id, name
            FROM datasource
            WHERE status = 1
              AND deleted = 0
            ORDER BY id
            """)
    List<java.util.Map<String, Object>> selectAllEnabledDatasources();
}
