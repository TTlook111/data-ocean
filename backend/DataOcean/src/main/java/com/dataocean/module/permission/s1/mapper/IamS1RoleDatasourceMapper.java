package com.dataocean.module.permission.s1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.permission.s1.entity.IamS1RoleDatasource;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** IAM-SIMPLE-1 用户角色负责源 Mapper。 */
@Mapper
public interface IamS1RoleDatasourceMapper extends BaseMapper<IamS1RoleDatasource> {

    @Delete("DELETE FROM iam_s1_role_datasource WHERE user_role_id = #{userRoleId}")
    int deleteByUserRoleId(@Param("userRoleId") Long userRoleId);
}
