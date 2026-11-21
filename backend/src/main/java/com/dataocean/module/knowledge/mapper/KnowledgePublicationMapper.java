package com.dataocean.module.knowledge.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 权威生效入口、发布快照及上一版历史。
 */
public interface KnowledgePublicationMapper {

    /**
     * 正式绑定由各自不可变版本组成，直接上一版按竞态保护后的真实入口保存。
     */
    record InsertBinding(
            long id,
            long scope,
            long candidate,
            String build,
            String validation,
            long documentRevision,
            long embeddingRevision,
            Long previous,
            long actor,
            String command) {}

    /**
     * 同源绑定及对应发布、文档和真实索引位置。
     */
    record Binding(
            long id,
            long scopeId,
            long sourceId,
            long taskId,
            long candidateId,
            long documentId,
            long documentRevisionId,
            int revision,
            String title,
            String buildId,
            String validationId,
            long embeddingRevisionId,
            Long previousBindingId,
            Long activeBindingId,
            String collectionName,
            long publishedBy,
            LocalDateTime createdAt,
            LocalDateTime verifiedAt) {}

    /**
     * 所有正式回读由固定绑定连接到当时实际发布的修订与代次。
     */
    String BINDING =
            """
            SELECT v.id,v.scope_id,s.source_id,c.task_id,v.candidate_id,s.document_id,v.document_revision_id,
                   d.revision,c.title,v.build_id,v.validation_id,v.embedding_revision_id,
                   v.previous_binding_id,s.active_binding_id,b.collection_name,v.published_by,
                   v.created_at,v.verified_at
            FROM kn_release_binding v JOIN kn_release_scope s ON s.id=v.scope_id
            JOIN kn_release_candidate c ON c.id=v.candidate_id
            JOIN kn_release_build b ON b.id=v.build_id
            JOIN kn_document_revision d ON d.id=v.document_revision_id
            """;

    /**
     * 固定本次切换实际使用的完整版本。
     */
    @Insert(
            """
            INSERT INTO kn_release_binding(id,scope_id,candidate_id,build_id,validation_id,
                document_revision_id,embedding_revision_id,previous_binding_id,published_by,command_id)
            VALUES(#{id},#{scope},#{candidate},#{build},#{validation},#{documentRevision},#{embeddingRevision},
                #{previous},#{actor},#{command})
            """)
    void insert(InsertBinding binding);

    /**
     * 在来源锁内用预检采用的基础版本做最后的原子比较与切换。
     */
    @Update(
            """
            UPDATE kn_release_scope SET active_binding_id=#{next}
            WHERE id=#{scope} AND source_id=#{source} AND active_binding_id <=> #{previous}
            """)
    int switchBinding(
            @Param("source") long source,
            @Param("scope") long scope,
            @Param("previous") Long previous,
            @Param("next") long next);

    /**
     * 发布提交与切换后确认分别更新同一任务阶段。
     */
    @Update(
            """
            UPDATE kn_release_candidate SET status=#{state},version=version+1
            WHERE id=#{candidate} AND status=#{previous}
            """)
    int advance(@Param("candidate") long candidate, @Param("previous") String previous, @Param("state") String state);

    /**
     * 只在真实权威组合重新读回后标明已核实时间。
     */
    @Update("UPDATE kn_release_binding SET verified_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND verified_at IS NULL")
    int markVerified(long id);

    /**
     * 按实际来源读取稳定绑定。
     */
    @Select(BINDING + " WHERE s.source_id=#{source} AND v.id=#{id}")
    Binding get(@Param("source") long source, @Param("id") long id);

    /**
     * 从原任务取得真实发布快照，包括已被新版替代的历史。
     */
    @Select(BINDING + " WHERE s.source_id=#{source} AND c.task_id=#{task} ORDER BY v.id DESC LIMIT 1")
    Binding published(@Param("source") long source, @Param("task") long task);

    /**
     * 当前生效只读取 MySQL 指针命中的完整版本。
     */
    @Select(
            BINDING
                    + """
            WHERE s.source_id=#{source} AND s.active_binding_id=v.id
              AND (#{scope} IS NULL OR s.id=#{scope})
              AND (#{keyword}='' OR LOCATE(#{keyword},c.title)>0)
              AND (#{object}='' OR EXISTS (
                 SELECT 1 FROM kn_document_source_ref ref
                 WHERE ref.revision_id=v.document_revision_id AND ref.kind='TABLE' AND LOCATE(#{object},ref.label)>0))
            ORDER BY v.id DESC
            """)
    Page<Binding> current(
            Page<Binding> page,
            @Param("source") long source,
            @Param("scope") Long scope,
            @Param("keyword") String keyword,
            @Param("object") String object);
}
