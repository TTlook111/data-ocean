package com.dataocean.module.knowledge.mapper;

import com.dataocean.module.knowledge.entity.po.KnowledgeReleaseCandidatePO;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 构建台账、候选指针、派发租约和固定片段在同一平台库内管理。
 */
public interface KnowledgeBuildMapper {

    /**
     * 实际构建及原始来源和操作身份。
     */
    record Build(
            String id,
            long candidateId,
            String collectionName,
            long sourceId,
            long documentId,
            long requestedBy,
            long authenticationVersion,
            String state,
            String failureCode,
            String dispatchToken,
            LocalDateTime modelClaimedAt,
            LocalDateTime createdAt,
            Integer vectorDimension,
            Integer verifiedChunks) {}

    /**
     * 锁定范围时固定的片段正文、证据映射和校验摘要。
     */
    record Chunk(int ordinal, String body, String evidence, String digest) {}

    /**
     * 用真实范围归属定位候选，不接受路径外的文档标识。
     */
    @Select(
            """
            SELECT c.*,s.document_id FROM kn_release_candidate c JOIN kn_release_scope s ON s.id=c.scope_id
            WHERE s.source_id=#{source} AND c.id=#{candidate}
            """)
    KnowledgeReleaseCandidatePO candidate(@Param("source") long source, @Param("candidate") long candidate);

    /**
     * 回读范围内的文档身份及当前基础指针。
     */
    @Select("SELECT document_id FROM kn_release_scope WHERE id=#{scope}")
    long document(@Param("scope") long scope);

    /**
     * 读取本候选固定的已审核题版本。
     */
    @Select(
            "SELECT test_revision_id FROM kn_release_candidate_test WHERE candidate_id=#{candidate} ORDER BY test_revision_id")
    List<Long> testRevisions(@Param("candidate") long candidate);

    /**
     * 将构建和候选关联写入同一个用户命令事务。
     */
    @Insert(
            """
            INSERT INTO kn_release_build (id,candidate_id,collection_name,requested_by,authentication_version,state)
            VALUES (#{id},#{candidate},#{collection},#{actor},#{authentication},'QUEUED')
            """)
    void insert(
            @Param("id") String id,
            @Param("candidate") long candidate,
            @Param("collection") String collection,
            @Param("actor") long actor,
            @Param("authentication") long authentication);

    /**
     * 只有准备或失败的现行候选能进入新一代构建。
     */
    @Update(
            """
            UPDATE kn_release_candidate SET current_build_id=#{build},status='BUILDING',version=version+1
            WHERE id=#{candidate} AND version=#{version} AND status IN ('PREPARED','BUILD_FAILED')
            """)
    int begin(@Param("candidate") long candidate, @Param("build") String build, @Param("version") int version);

    /**
     * 终态回写必须仍属于当前构建代次。
     */
    @Update(
            """
            UPDATE kn_release_candidate SET status=#{state},version=version+1
            WHERE id=#{candidate} AND current_build_id=#{build} AND status='BUILDING'
            """)
    int end(@Param("candidate") long candidate, @Param("build") String build, @Param("state") String state);

    /**
     * 已取消候选无法继续接收新一轮构建。
     */
    @Update(
            """
            UPDATE kn_release_candidate SET status='CANCELED',version=version+1
            WHERE id=#{id} AND version=#{version} AND status IN
                ('PREPARED','BUILDING','BUILD_FAILED','READY_FOR_VALIDATION')
            """)
    int cancel(@Param("id") long candidate, @Param("version") int version);

    /**
     * 保留已经验证完的历史，只撤销仍处于在途状态的尝试。
     */
    @Update(
            """
            UPDATE kn_release_build SET state='CANCELED',finished_at=UTC_TIMESTAMP(3),dispatch_token=NULL
            WHERE id=#{id} AND state IN ('QUEUED','RUNNING')
            """)
    void cancelAttempt(@Param("id") String id);

    /**
     * 固定单个完整章节及其来源映射。
     */
    @Insert(
            """
            INSERT INTO kn_release_build_chunk (build_id,ordinal,body,evidence,digest)
            VALUES (#{build},#{part.ordinal},#{part.body},#{part.evidence},#{part.digest})
            """)
    void addChunk(@Param("build") String build, @Param("part") Chunk part);

    /**
     * 按确切构建代次读取完整片段清单。
     */
    @Select("SELECT ordinal,body,evidence,digest FROM kn_release_build_chunk WHERE build_id=#{build} ORDER BY ordinal")
    List<Chunk> chunks(@Param("build") String build);

    /**
     * 构建上下文始终从真实候选和源范围取得。
     */
    String BUILD =
            """
            SELECT b.id,b.candidate_id,b.collection_name,s.source_id,s.document_id,b.requested_by,b.authentication_version,
                   b.state,b.failure_code,b.dispatch_token,b.model_claimed_at,b.created_at,b.vector_dimension,b.verified_chunks
            FROM kn_release_build b JOIN kn_release_candidate c ON c.id=b.candidate_id
              JOIN kn_release_scope s ON s.id=c.scope_id
            """;

    /**
     * 当前任务和固定归属。
     */
    @Select(BUILD + " WHERE b.id=#{id}")
    Build get(@Param("id") String id);

    /**
     * 最新构建在首位，历史失败仍可回查。
     */
    @Select(BUILD + " WHERE b.candidate_id=#{candidate} ORDER BY b.created_at DESC,b.id DESC")
    List<Build> history(@Param("candidate") long candidate);

    /**
     * 多派发进程只领取当前可用的一条持久记录。
     */
    @Select(
            """
            SELECT id FROM kn_release_build WHERE state IN ('QUEUED','RUNNING') AND next_poll_at<=UTC_TIMESTAMP(3)
            ORDER BY next_poll_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    String next();

    /**
     * 预留有界派发窗口，超时后仍以原编号重新核实。
     */
    @Update(
            """
            UPDATE kn_release_build SET dispatch_token=#{token},next_poll_at=UTC_TIMESTAMP(3)+INTERVAL 12 SECOND
            WHERE id=#{id}
            """)
    void reserve(@Param("id") String id, @Param("token") String token);

    /**
     * 确认仍为原派发者后允许下一轮读取同一任务。
     */
    @Update(
            """
            UPDATE kn_release_build SET next_poll_at=UTC_TIMESTAMP(3)+INTERVAL 1 SECOND,dispatch_token=NULL
            WHERE id=#{id} AND dispatch_token=#{token} AND state IN ('QUEUED','RUNNING')
            """)
    void postpone(@Param("id") String id, @Param("token") String token);

    /**
     * 同一尝试只领取一次外发资格，Worker 接管不能盲目重发未知模型请求。
     */
    @Update(
            """
            UPDATE kn_release_build SET state='RUNNING',model_claimed_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND state='QUEUED' AND model_claimed_at IS NULL
            """)
    int claimModel(@Param("id") String id);

    /**
     * 旧派发、超时代次及已取消状态均不能提交结果。
     */
    @Update(
            """
            UPDATE kn_release_build SET state=#{state},failure_code=#{failure},finished_at=UTC_TIMESTAMP(3),
                dispatch_token=NULL,vector_dimension=#{dimension},verified_chunks=#{chunks},retrieval_ids=#{retrieval}
            WHERE id=#{id} AND dispatch_token=#{token} AND state IN ('QUEUED','RUNNING')
            """)
    int finish(
            @Param("id") String id,
            @Param("token") String token,
            @Param("state") String state,
            @Param("failure") String failure,
            @Param("dimension") Integer dimension,
            @Param("chunks") Integer chunks,
            @Param("retrieval") String retrieval);
}
