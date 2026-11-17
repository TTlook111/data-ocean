package com.dataocean.module.iam.mapper;

import com.dataocean.module.iam.entity.po.CommandPO;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 公共契约的持久化适配，业务状态和权限由所属服务核验。
 */
public interface CommandMapper {

    /**
     * 按稳定标识读取当前记录，由所属服务继续校验可见性和状态。
     */
    @Select(
            "SELECT * FROM sys_command WHERE principal_kind='USER' AND principal_id=#{actor} AND operation_code=#{operation} AND command_id=#{command}")
    CommandPO find(
            @Param("actor") String actor, @Param("operation") String operation, @Param("command") String command);

    /**
     * 在所属业务事务内保存明确类型记录。
     */
    @Insert(
            "INSERT INTO sys_command(principal_kind,principal_id,actor_user_id,operation_code,command_id,payload_hmac,hmac_key_version,state,created_at) VALUES('USER',#{actor},#{actor},#{operation},#{command},#{fingerprint},'1','ACCEPTED',UTC_TIMESTAMP(3))")
    int insert(
            @Param("actor") long actor,
            @Param("operation") String operation,
            @Param("command") String command,
            @Param("fingerprint") byte[] fingerprint);

    /**
     * 在业务成功后将同主体原命令更新为已提交并保存安全目标引用。
     */
    @Update(
            "UPDATE sys_command SET state='COMMITTED',result_code='OK',result_ref=#{result},completed_at=UTC_TIMESTAMP(3) WHERE principal_kind='USER' AND principal_id=#{actor} AND operation_code=#{operation} AND command_id=#{command}")
    int complete(
            @Param("actor") long actor,
            @Param("operation") String operation,
            @Param("command") String command,
            @Param("result") String result);
}
