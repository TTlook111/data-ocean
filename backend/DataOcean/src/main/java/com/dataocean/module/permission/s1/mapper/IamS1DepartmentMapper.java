package com.dataocean.module.permission.s1.mapper;

import com.dataocean.module.permission.s1.entity.IamS1DepartmentNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** S1 只读取真实部门树，不从部门名称推断业务权限。 */
@Mapper
public interface IamS1DepartmentMapper {

    @Select("""
            SELECT id, parent_id, status
            FROM sys_department
            """)
    List<IamS1DepartmentNode> selectAll();

    @Select("""
            SELECT id, parent_id, status
            FROM sys_department
            WHERE id = #{departmentId}
            """)
    IamS1DepartmentNode selectById(@Param("departmentId") Long departmentId);
}
