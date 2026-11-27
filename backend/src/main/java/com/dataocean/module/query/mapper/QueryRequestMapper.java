package com.dataocean.module.query.mapper;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 单条请求状态的防重、抢占、执行和交付条件。
 */
public interface QueryRequestMapper {

    /**
     * 权威请求记录，正文和安全检查只在对应任务内部消费。
     *
     * @param failureGapKey 已核实知识缺口的受控指纹，供可靠入队复用开放事项
     */
    record Request(
            String id,
            long userId,
            long authenticationVersion,
            long sourceId,
            String commandId,
            String previousId,
            String question,
            String clarification,
            String supplement,
            String clarificationCommand,
            boolean cancelRequested,
            String state,
            String executionState,
            String executionId,
            Long bindingId,
            String ruleSignature,
            String reasoning,
            String result,
            int remainingMillis,
            LocalDateTime processingStartedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime executedAt,
            String conversationId,
            boolean inheritContext,
            int runVersion,
            LocalDateTime leaseUntil,
            String runInput,
            byte[] commandFingerprint,
            byte[] executionFingerprint,
            String usageRefs,
            String failurePhase,
            String failureCode,
            String failureRuleSignature,
            String failureGapKey,
            String failureEventId,
            String recheckOriginId) {}

    /** 写回执行许可时固定的已校验输入。 */
    record Permit(
            String id,
            int run,
            String execution,
            long binding,
            String signature,
            String reasoning,
            byte[] fingerprint,
            String usageRefs) {}

    /** 轮次与其初次请求的关系。 */
    record Input(
            String id,
            long user,
            long authentication,
            long source,
            String command,
            String previous,
            String question,
            String conversation,
            boolean inherit,
            byte[] fingerprint,
            String recheckOriginId,
            String supplement) {}

    /** 使用同一业务主键回读，调用方核验来源和本人。 */
    @Select("SELECT * FROM qry_request WHERE id=#{id}")
    Request get(String id);

    /** 解释幂等命令在原请求锁内串行提交。 */
    @Select("SELECT * FROM qry_request WHERE id=#{id} FOR UPDATE")
    Request lock(String id);

    /** 仅锁和当前提问人匹配的回答。 */
    @Select("SELECT * FROM qry_request WHERE id=#{id} AND user_id=#{user} FOR UPDATE")
    Request lockOwned(String id, long user);

    /** 同用户的同一原命令可恢复提交。 */
    @Select("SELECT * FROM qry_request WHERE user_id=#{user} AND command_id=#{command}")
    Request byCommand(long user, String command);

    /** 源端执行尚未核实时，同样的问题不能借新编号重复派发。 */
    @Select(
            """
            SELECT EXISTS(SELECT 1 FROM qry_request WHERE user_id=#{user} AND source_id=#{source}
                AND question=#{question} AND (execution_state='UNKNOWN' OR state='EXECUTING'))
            """)
    boolean hasUnresolved(long user, long source, String question);

    /** 改写问题或调整等值条件顺序仍不能重复派发同一已核验语句。 */
    @Select(
            """
            SELECT EXISTS(SELECT 1 FROM qry_request WHERE user_id=#{user} AND source_id=#{source}
                AND execution_fingerprint=#{fingerprint} AND id<>#{id}
                AND execution_state IN ('RUNNING','UNKNOWN'))
            """)
    boolean hasUnresolvedExecution(long user, long source, byte[] fingerprint, String id);

    /** 同来源近期轮次不返回其他用户的记录。 */
    @Select(
            """
            SELECT id,question,state,created_at FROM qry_request
            WHERE user_id=#{user} AND source_id=#{source} AND created_at >= UTC_TIMESTAMP(3)-INTERVAL 90 DAY
            ORDER BY created_at DESC,id DESC LIMIT 30
            """)
    List<History> history(long user, long source);

    /** 只恢复当前复验人就原问、当期提交时间派发的最近轮次。 */
    @Select(
            """
            SELECT id FROM qry_request
            WHERE recheck_origin_id=#{origin} AND user_id=#{actor} AND created_at >= #{submittedAt}
            ORDER BY created_at DESC,id DESC LIMIT 1
            """)
    String latestFeedbackReplay(String origin, long actor, LocalDateTime submittedAt);

    /** 轮次列表只加载定位字段。 */
    record History(String id, String question, String state, LocalDateTime createdAt) {}

    /** 抢占命令键，从并行或网络重试返回原轮次。 */
    @Insert(
            """
            INSERT INTO qry_request(id,user_id,authentication_version,source_id,command_id,previous_id,
                question,conversation_id,inherit_context,command_fingerprint,state,recheck_origin_id,supplement)
            VALUES(#{id},#{user},#{authentication},#{source},#{command},#{previous},
                #{question},#{conversation},#{inherit},#{fingerprint},'QUEUED',#{recheckOriginId},#{supplement})
            ON DUPLICATE KEY UPDATE id=id
            """)
    void insert(Input input);

    /**
     * 领取可恢复的旧代次，已派发状态仅用于核实原 SQL。
     */
    @Select(
            """
            SELECT * FROM qry_request
            WHERE state='QUEUED' OR (state IN ('RETRIEVING','GENERATING','CHECKING','EXECUTING','FINALIZING')
                AND run_version>0 AND lease_until<=UTC_TIMESTAMP(3))
            ORDER BY (state='FINALIZING') DESC,created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    Request next();

    /**
     * 为本次处理或核实领取有代次的租约。
     */
    @Update(
            """
            UPDATE qry_request SET state=IF(state='QUEUED','RETRIEVING',state),
                processing_started_at=COALESCE(processing_started_at,UTC_TIMESTAMP(3)),
                run_version=run_version+1,lease_until=UTC_TIMESTAMP(3)+INTERVAL 12 SECOND,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND run_version=#{run}
            """)
    int claim(String id, int run);

    /** 心跳只续当前代次，过期旧进程不能重新激活租约。 */
    @Update(
            """
            UPDATE qry_request SET lease_until=UTC_TIMESTAMP(3)+INTERVAL 12 SECOND
            WHERE id=#{id} AND run_version=#{run} AND lease_until>UTC_TIMESTAMP(3)
              AND state IN ('RETRIEVING','GENERATING','CHECKING','EXECUTING','FINALIZING')
            """)
    int renew(String id, int run);

    /** 模型配置、同版索引入口与对话在原轮次第一次冻结。 */
    @Update(
            """
            UPDATE qry_request SET run_input=#{input} WHERE id=#{id} AND run_version=#{run}
              AND lease_until>UTC_TIMESTAMP(3) AND run_input IS NULL AND cancel_requested=FALSE
            """)
    int freeze(String id, int run, String input);

    /**
     * 首次选定的历史摘要或确定性回退先于理解和规划节点持久化。
     */
    @Update(
            """
            UPDATE qry_request SET run_input=JSON_SET(run_input,'$.historySummary',CAST(#{summary} AS JSON))
            WHERE id=#{id} AND run_version=#{run} AND lease_until>UTC_TIMESTAMP(3) AND cancel_requested=FALSE
              AND run_input IS NOT NULL
              AND COALESCE(JSON_TYPE(JSON_EXTRACT(run_input,'$.historySummary')),'NULL')='NULL'
            """)
    int freezeSummary(String id, int run, String summary);

    /**
     * 规划前保存每个阶段采用的候选顺序，澄清和租约接管不能重放外发。
     */
    @Update(
            """
            UPDATE qry_request SET run_input=JSON_SET(run_input,#{path},CAST(#{decision} AS JSON))
            WHERE id=#{id} AND run_version=#{run} AND lease_until>UTC_TIMESTAMP(3) AND cancel_requested=FALSE
              AND run_input IS NOT NULL
              AND COALESCE(JSON_TYPE(JSON_EXTRACT(run_input,#{path})),'NULL')='NULL'
            """)
    int freezeRerank(String id, int run, String path, String decision);

    /** 状态变化只允许属于本次活动且未取消的在途请求。 */
    @Update(
            """
            UPDATE qry_request SET state=IF(state='CHECKING',state,#{state}),updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND run_version=#{run} AND lease_until>UTC_TIMESTAMP(3)
              AND state IN ('RETRIEVING','GENERATING','CHECKING') AND cancel_requested=FALSE
            """)
    int stage(String id, int run, String state);

    /**
     * 等待用户补充时释放当前工作者的处理资格。
     */
    @Update(
            """
            UPDATE qry_request SET state='WAITING',clarification=#{text},updated_at=UTC_TIMESTAMP(3),
                processing_started_at=NULL,lease_until=NULL
            WHERE id=#{id} AND run_version=#{run} AND lease_until>UTC_TIMESTAMP(3)
              AND state='GENERATING' AND cancel_requested=FALSE AND clarification_command IS NULL
            """)
    int waitForAnswer(String id, int run, String text);

    /** 等待有效期内原轮次恢复并保留当次补充防重键。 */
    @Update(
            """
            UPDATE qry_request SET state='QUEUED',supplement=#{answer},clarification_command=#{command},
            updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND state='WAITING' AND updated_at>UTC_TIMESTAMP(3)-INTERVAL 30 MINUTE
            """)
    int answer(String id, String command, String answer);

    /** 派发事实在业务数据库执行之前提交，进程接管时不得重复执行。 */
    @Update(
            """
            UPDATE qry_request SET state='EXECUTING',execution_state='RUNNING',execution_id=#{execution},
                binding_id=#{binding},rule_signature=#{signature},reasoning=#{reasoning},
                execution_fingerprint=#{fingerprint},usage_refs=#{usageRefs},
            updated_at=UTC_TIMESTAMP(3),executed_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND run_version=#{run} AND lease_until>UTC_TIMESTAMP(3)
              AND state='CHECKING' AND execution_id IS NULL AND cancel_requested=FALSE
            """)
    int permit(Permit input);

    /**
     * 完整捕获原执行的结果，旧代次和不同执行编号不能写回。
     */
    @Update(
            """
            UPDATE qry_request SET state='FINALIZING',execution_state='SUCCEEDED',result=#{data},
                updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND run_version=#{run} AND lease_until>UTC_TIMESTAMP(3)
              AND state='EXECUTING' AND execution_id=#{execution}
            """)
    int capture(String id, int run, String execution, String data);

    /**
     * 回读同一轮次真正登记的源执行及已捕获结果。
     */
    @Select("SELECT * FROM qry_request WHERE id=#{id} AND execution_id=#{execution}")
    Request byExecution(String id, String execution);

    /**
     * 等待核实原 SQL，不释放原执行编号，也不回到 SQL 派发阶段。
     */
    @Update(
            """
            UPDATE qry_request SET execution_state='UNKNOWN',lease_until=UTC_TIMESTAMP(3)+INTERVAL 2 SECOND,
                updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND run_version=#{run} AND execution_id=#{execution} AND state='EXECUTING'
              AND lease_until>UTC_TIMESTAMP(3)
            """)
    int verifyExecution(String id, int run, String execution);

    /**
     * 原子保存重新授权后的交付时间和状态，说明额度仅用于说明。
     */
    @Update(
            """
            UPDATE qry_request SET state='RESULT_DELIVERED',lease_until=NULL,updated_at=UTC_TIMESTAMP(3),
                delivered_at=UTC_TIMESTAMP(3),
                processing_started_at=NULL
            WHERE id=#{id} AND run_version=#{run} AND lease_until>UTC_TIMESTAMP(3)
              AND state='FINALIZING' AND cancel_requested=FALSE
              AND result IS NOT NULL AND executed_at>UTC_TIMESTAMP(3)-INTERVAL 1 DAY
            """)
    int deliver(String id, int run);

    /**
     * 从该轮独立的说明额度预留本次调用，进程失联也不复用。
     */
    @Update(
            """
            UPDATE qry_request SET remaining_millis=remaining_millis-#{millis}
            WHERE id=#{id} AND state='RESULT_DELIVERED' AND remaining_millis>=#{millis}
            """)
    int reserveExplanation(String id, int millis);

    /** 停止待办请求，在途请求仅登记取消，实际 SQL 状态随后回写。 */
    @Update(
            """
            UPDATE qry_request SET cancel_requested=TRUE,
            state=CASE WHEN state IN ('QUEUED','WAITING') THEN 'USER_CANCELLED' ELSE state END,
            updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND state IN ('QUEUED','WAITING','RETRIEVING','GENERATING','CHECKING','EXECUTING','FINALIZING')
             AND cancel_requested=FALSE
            """)
    int cancel(String id);

    /** 精确记录请求终态和 SQL 的独立结果。 */
    @Update(
            """
            UPDATE qry_request SET
            failure_phase=CASE WHEN NOT cancel_requested AND #{diagnostic} IS NOT NULL THEN state ELSE NULL END,
            failure_code=CASE WHEN NOT cancel_requested THEN #{diagnostic} ELSE NULL END,
            failure_rule_signature=CASE WHEN NOT cancel_requested AND #{diagnostic} IS NOT NULL
                THEN COALESCE(rule_signature,#{signature}) ELSE NULL END,
            failure_gap_key=CASE WHEN NOT cancel_requested AND #{state}='KNOWLEDGE_INSUFFICIENT'
                THEN #{gapKey} ELSE NULL END,
            state=CASE WHEN cancel_requested THEN 'USER_CANCELLED' ELSE #{state} END,
            execution_state=#{execution},result=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND run_version=#{run} AND lease_until>UTC_TIMESTAMP(3)
              AND state IN ('RETRIEVING','GENERATING','CHECKING','EXECUTING','FINALIZING')
            """)
    int stop(String id, int run, String state, String execution, String diagnostic, String signature, String gapKey);

    /**
     * 回收过期澄清及无法接管的旧格式轮次，当前代次由工作者核实。
     */
    @Update(
            """
            UPDATE qry_request SET
            failure_phase=CASE WHEN state<>'WAITING' AND NOT cancel_requested THEN state ELSE NULL END,
            failure_code=CASE WHEN state<>'WAITING' AND NOT cancel_requested THEN 'QUERY_LEASE_EXPIRED' ELSE NULL END,
            failure_rule_signature=CASE WHEN state<>'WAITING' AND NOT cancel_requested
                THEN COALESCE(rule_signature,JSON_UNQUOTE(JSON_EXTRACT(run_input,'$.signature'))) ELSE NULL END,
            execution_state=CASE WHEN state='EXECUTING' THEN 'UNKNOWN' ELSE execution_state END,
            state=CASE WHEN state='WAITING' THEN 'WAIT_EXPIRED'
                WHEN cancel_requested THEN 'USER_CANCELLED' ELSE 'TECHNICAL_FAILURE' END,
            updated_at=UTC_TIMESTAMP(3)
            WHERE (state='WAITING' AND updated_at<=UTC_TIMESTAMP(3)-INTERVAL 30 MINUTE)
               OR (state IN ('RETRIEVING','GENERATING','CHECKING','EXECUTING') AND run_version=0
                   AND processing_started_at<=UTC_TIMESTAMP(3)-INTERVAL 165 SECOND)
            """)
    void expire();

    /** 分批从已经提交的失败中领取原请求，历史终态没有该标记。 */
    @Select(
            """
            SELECT id FROM qry_request WHERE failure_event_id IS NULL AND failure_code IS NOT NULL
                AND recheck_origin_id IS NULL
                AND state IN ('KNOWLEDGE_INSUFFICIENT','TECHNICAL_FAILURE') ORDER BY updated_at,id LIMIT 50
            """)
    List<String> pendingIssueRequests();

    /** 记录已接收的 Outbox 编号，不改变原问更新时刻和终态。 */
    @Update("UPDATE qry_request SET failure_event_id=#{event} WHERE id=#{id} AND failure_event_id IS NULL")
    int markIssueEvent(String id, String event);

    /** 按权威原请求绑定识别待分派事件，不依赖异步载荷声称的来源。 */
    @Select("SELECT id FROM qry_request WHERE failure_event_id=#{event}")
    String issueEventRequest(String event);

    /** 限量结果保留期与请求事实分开。 */
    @Update(
            """
            UPDATE qry_request SET result=NULL
            WHERE result IS NOT NULL AND executed_at<=UTC_TIMESTAMP(3)-INTERVAL 1 DAY
            """)
    void expireResults();
}
