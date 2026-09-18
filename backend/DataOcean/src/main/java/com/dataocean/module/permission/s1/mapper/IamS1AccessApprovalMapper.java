package com.dataocean.module.permission.s1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.permission.s1.entity.IamS1AccessApproval;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** IAM-SIMPLE-1 访问审批 Mapper。只访问 iam_s1_* 表。 */
@Mapper
public interface IamS1AccessApprovalMapper extends BaseMapper<IamS1AccessApproval> {

    @Select("""
            SELECT id, request_id, reviewer_id, decision, approved_columns_json,
                   approved_valid_from, approved_valid_until, generated_grant_id, reason, created_at
            FROM iam_s1_access_approval
            WHERE request_id = #{requestId}
            """)
    IamS1AccessApproval selectByRequestId(@Param("requestId") Long requestId);
}
