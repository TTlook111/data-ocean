package com.dataocean.module.iam.mapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.iam.entity.po.CommandPO;


public interface CommandMapper {
    @Select("SELECT * FROM sys_command WHERE principal_kind='USER' AND principal_id=#{actor} AND operation_code=#{operation} AND command_id=#{command}")
    CommandPO find(@Param("actor") String actor,@Param("operation") String operation,@Param("command") String command);
    @Insert("INSERT INTO sys_command(principal_kind,principal_id,actor_user_id,operation_code,command_id,payload_hmac,hmac_key_version,state,created_at) VALUES('USER',#{actor},#{actor},#{operation},#{command},#{fingerprint},'1','ACCEPTED',UTC_TIMESTAMP(3))")
    int insert(@Param("actor") long actor,@Param("operation") String operation,@Param("command") String command,@Param("fingerprint") byte[] fingerprint);
    @Update("UPDATE sys_command SET state='COMMITTED',result_code='OK',result_ref=#{result},completed_at=UTC_TIMESTAMP(3) WHERE principal_kind='USER' AND principal_id=#{actor} AND operation_code=#{operation} AND command_id=#{command}")
    int complete(@Param("actor") long actor,@Param("operation") String operation,@Param("command") String command,@Param("result") String result);
}
