package com.dataocean.module.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.knowledge.entity.po.KnowledgeReleaseCandidatePO;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 保存候选、来源范围及固定题单修订。
 */
public interface KnowledgeReleaseCandidateMapper extends BaseMapper<KnowledgeReleaseCandidatePO> {

    /**
     * 当前范围身份及其权威基础指针。
     */
    record Scope(long id, Long activeBindingId) {}

    /**
     * 旧候选的冻结文档和原处理阶段。
     */
    record HistoricalCandidate(
            long id, String title, long documentId, long documentRevisionId, int revision, String status) {}

    /**
     * 读取原任务的旧候选。当前指针为空时保留全部历史。
     */
    @Select(
            """
            SELECT c.id,c.title,s.document_id,c.document_revision_id,r.revision,c.status
            FROM kn_release_candidate c
            JOIN kn_release_scope s ON s.id=c.scope_id
            JOIN kn_document_revision r ON r.id=c.document_revision_id
            JOIN kn_release_task t ON t.id=c.task_id
            WHERE t.id=#{task} AND (t.candidate_id IS NULL OR c.id<>t.candidate_id)
            ORDER BY c.id DESC
            """)
    List<HistoricalCandidate> history(@Param("task") long task);

    /**
     * 仅按同一来源及文档定位发布范围。
     */
    @Select("SELECT id,active_binding_id FROM kn_release_scope WHERE source_id=#{source} AND document_id=#{document}")
    Scope scope(@Param("source") long source, @Param("document") long document);

    /**
     * 在持有源锁的业务事务内登记首次使用的范围。
     */
    @Insert("INSERT INTO kn_release_scope (id,source_id,document_id) VALUES (#{id},#{source},#{document})")
    void createScope(@Param("id") long id, @Param("source") long source, @Param("document") long document);

    /**
     * 为本候选持久化一个实际审核题的修订身份。
     */
    @Insert("INSERT INTO kn_release_candidate_test (candidate_id,test_revision_id) VALUES (#{id},#{revision})")
    void attachTest(@Param("id") long id, @Param("revision") long revision);
}
