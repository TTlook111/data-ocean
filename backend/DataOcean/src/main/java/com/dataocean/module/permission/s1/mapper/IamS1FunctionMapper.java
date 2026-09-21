package com.dataocean.module.permission.s1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.permission.s1.entity.IamS1Function;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** IAM-SIMPLE-1 固定功能目录 Mapper。 */
@Mapper
public interface IamS1FunctionMapper extends BaseMapper<IamS1Function> {

    @Select("""
            SELECT id, function_code, function_name, function_description,
                   business_domain, workspace, status, dependency_codes,
                   created_at, updated_at
            FROM iam_s1_function
            WHERE function_code = #{functionCode}
              AND status = 'ACTIVE'
            """)
    IamS1Function selectActiveByCode(@Param("functionCode") String functionCode);
}
