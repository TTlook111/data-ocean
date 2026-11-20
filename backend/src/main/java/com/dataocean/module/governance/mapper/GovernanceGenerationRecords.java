package com.dataocean.module.governance.mapper;

import com.dataocean.module.governance.entity.po.GovernanceGenerationPO;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 治理任务固定目标、输入与持久派发租约。
 */
public interface GovernanceGenerationRecords {

    /**
     * 并发读取一笔有资格办理的原任务，远程调用在事务外执行。
     */
    @Select(
            """
            SELECT * FROM gov_generation_task
            WHERE state IN ('PENDING_DISPATCH','QUEUED','RUNNING') AND due_at<=UTC_TIMESTAMP(3)
            ORDER BY due_at,request_id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    GovernanceGenerationPO next();

    /**
     * 保存本次背景回读的临时资格。失败或退出后可再次获取同一任务。
     */
    @Update(
            """
            UPDATE gov_generation_task SET lease_token=#{lease},due_at=TIMESTAMPADD(SECOND,12,UTC_TIMESTAMP(3))
            WHERE request_id=#{id}
            """)
    int claim(@Param("id") String id, @Param("lease") String lease);

    /**
     * 只接受当前背景租约推进的队列状态或终态。
     */
    @Update(
            """
            UPDATE gov_generation_task SET
                started_at=IF(#{state} IN ('RUNNING','SUCCEEDED'),COALESCE(started_at,UTC_TIMESTAMP(3)),started_at),
                version=version+IF(state!=#{state},1,0),state=#{state},lease_token=NULL,
                due_at=TIMESTAMPADD(SECOND,#{delay},UTC_TIMESTAMP(3)),failure_code=#{failure},
                ended_at=IF(#{state} IN ('SUCCEEDED','FAILED'),UTC_TIMESTAMP(3),NULL)
            WHERE request_id=#{id} AND lease_token=#{lease}
            """)
    int advance(
            @Param("id") String id,
            @Param("lease") String lease,
            @Param("state") String state,
            @Param("failure") String failure,
            @Param("delay") int delay);

    /**
     * 最终结果核对在真正写入时锁定原任务。
     */
    @Select("SELECT * FROM gov_generation_task WHERE request_id=#{id} FOR UPDATE")
    GovernanceGenerationPO lock(String id);

    /**
     * 以接收时的顺序读取同批独立执行项。
     */
    @Select("SELECT * FROM gov_generation_task WHERE batch_request_id=#{request} ORDER BY batch_position")
    List<GovernanceGenerationPO> batchMembers(String request);

    /**
     * 持久保存接收时的稳定目标和表说明范围。
     */
    @Insert(
            """
            INSERT INTO gov_generation_target(
                request_id,item_id,base_item_version,object_revision_id,column_revision_id,content_paths
            )
            VALUES(#{request},#{item},#{version},#{revision},#{columnRevision},#{paths})
            """)
    int insertTarget(
            @Param("request") String request,
            @Param("item") long item,
            @Param("version") int version,
            @Param("revision") long revision,
            @Param("columnRevision") Long columnRevision,
            @Param("paths") String paths);

    /**
     * 从同一稳定治理条目读取实际表或字段身份。
     */
    @Select(
            """
            SELECT t.item_id,t.base_item_version,t.object_revision_id,t.column_revision_id,t.content_paths,
                   i.kind,i.target_id
            FROM gov_generation_target t JOIN gov_item i ON i.id=t.item_id
            WHERE t.request_id=#{id}
            """)
    Target target(String id);

    /**
     * 先登记当前任务的确切资料输入，资料防删除与 task 同一事务。
     */
    @Insert(
            """
            INSERT INTO gov_generation_input(request_id,note_id,note_revision_id,state)
            VALUES(#{request},#{note},#{revision},'REGISTERED')
            """)
    int addInput(@Param("request") String request, @Param("note") long note, @Param("revision") long revision);

    /**
     * 通过原任务及固定修订读取已经接受的实际资料范围。
     */
    @Select(
            "SELECT note_id,note_revision_id,state FROM gov_generation_input WHERE request_id=#{request} ORDER BY note_id")
    List<Input> inputs(String request);

    /**
     * 当次实际取用或跳过的资料留下可回读的身份及时间。
     */
    @Update(
            """
            UPDATE gov_generation_input SET state=#{state},processed_at=UTC_TIMESTAMP(3)
            WHERE request_id=#{request} AND note_id=#{note}
            """)
    int inputState(@Param("request") String request, @Param("note") long note, @Param("state") String state);

    /**
     * 固定生成目标的业务和结构版本。
     *
     * @param itemId 治理条目
     * @param baseItemVersion 接收后的业务版本
     * @param objectRevisionId 接收时表结构修订
     * @param columnRevisionId 接收时字段结构修订，表说明为空
     * @param contentPaths 实际允许模型生成的字段集合
     * @param kind 稳定条目类别
     * @param targetId 实际表或字段标识
     */
    record Target(
            long itemId,
            int baseItemVersion,
            long objectRevisionId,
            Long columnRevisionId,
            String contentPaths,
            String kind,
            long targetId) {}

    /**
     * 任务实际取得资格的资料修订。
     *
     * @param noteId 稳定资料
     * @param noteRevisionId 不可变修订
     * @param state 取用状态
     */
    record Input(long noteId, long noteRevisionId, String state) {}
}
