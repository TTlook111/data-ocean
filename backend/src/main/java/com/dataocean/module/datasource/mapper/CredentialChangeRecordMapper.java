package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.datasource.entity.po.CredentialChangePO;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 提供 CredentialChangePO 对应单表的持久化操作。
 */
public interface CredentialChangeRecordMapper extends BaseMapper<CredentialChangePO> {

    /**
     * 保存待停止的替换请求和固定核对依据，接收时刻使用数据库时钟。
     */
    @Insert(
            """
            INSERT INTO ds_connection_change (id,command_id,source_id,actor_id,base_connection_revision_id,check_id,
                candidate_credential_revision_id,expected_source_version,candidate_account,review_evidence,blocking_task_id,
                state,accepted_at)
            VALUES (#{change.id},#{change.commandId},#{change.sourceId},#{change.actorId},#{change.baseConnectionRevisionId},
                #{change.checkId},#{change.candidateCredentialRevisionId},#{change.expectedSourceVersion},
                #{change.candidateAccount},#{evidence},#{change.blockingTaskId},'PENDING_STOP',UTC_TIMESTAMP(3))
            """)
    int accept(@Param("change") CredentialChangePO change, @Param("evidence") String evidence);

    /**
     * 记录仍在等待停止的替换操作结论和数据库时刻。
     */
    @Update(
            """
            UPDATE ds_connection_change SET state=#{state},stop_confirmed_at=UTC_TIMESTAMP(3),completed_at=UTC_TIMESTAMP(3),
                applied_connection_revision_id=#{revision},failure_code=#{code},failure_message=#{message}
            WHERE id=#{id} AND state='PENDING_STOP'
            """)
    int finish(
            @Param("id") String id,
            @Param("state") String state,
            @Param("revision") Long revision,
            @Param("code") String code,
            @Param("message") String message);

    /**
     * 仅调度当前来源已无在途采集的凭据替换。
     */
    @Select(
            """
            SELECT c.* FROM ds_connection_change c JOIN ds_source s ON s.id=c.source_id
            WHERE c.state='PENDING_STOP' AND s.active_task_id IS NULL ORDER BY c.accepted_at,c.id LIMIT 16
            """)
    List<CredentialChangePO> ready();
}
