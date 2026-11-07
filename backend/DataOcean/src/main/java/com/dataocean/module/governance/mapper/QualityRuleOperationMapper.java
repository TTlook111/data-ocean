package com.dataocean.module.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.governance.entity.po.QualityRuleOperation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 唯一键同时协调原请求、重试和未知操作终结；重复请求不得覆盖已存指纹。 */
@Mapper
public interface QualityRuleOperationMapper extends BaseMapper<QualityRuleOperation> {
    @Insert("""
            INSERT INTO quality_rule_operation (operator_user_id, operation_id, request_hash, action, outcome, created_at)
            VALUES (#{operatorUserId}, #{operationId}, #{requestHash}, #{action}, 'PENDING', CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE operation_id = operation_id
            """)
    int reserve(QualityRuleOperation operation);

    @Select("""
            SELECT * FROM quality_rule_operation
            WHERE operator_user_id = #{userId} AND operation_id = #{operationId} FOR UPDATE
            """)
    QualityRuleOperation selectForUpdate(@Param("userId") Long userId, @Param("operationId") String operationId);
}
