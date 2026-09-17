package com.dataocean.module.permission.s1.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.permission.s1.entity.IamS1BootstrapState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** IAM-SIMPLE-1 bootstrap 状态 Mapper。 */
@Mapper
public interface IamS1BootstrapStateMapper extends BaseMapper<IamS1BootstrapState> {

    @Select("""
            SELECT id, protocol_version, state, target_user_id, completed_at,
                   implementation_version, execution_id, created_at, updated_at
            FROM iam_s1_bootstrap_state
            WHERE protocol_version = #{protocolVersion}
            FOR UPDATE
            """)
    IamS1BootstrapState selectByProtocolForUpdate(@Param("protocolVersion") String protocolVersion);
}
