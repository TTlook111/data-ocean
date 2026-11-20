package com.dataocean.module.knowledge.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 维持文档指针、不可变正文及分节引用。
 */
public interface KnowledgeDocumentMapper {

    /**
     * 聚合查询使用的共同列。
     */
    String DOCUMENT =
            """
            SELECT id,source_id,title,head_revision_id,confirmed_revision_id,confirmed_by,confirmed_at,version
            FROM kn_document
            """;

    /**
     * 文档聚合的持久化读视图。
     */
    record Document(
            long id,
            long sourceId,
            String title,
            long headRevisionId,
            Long confirmedRevisionId,
            Long confirmedBy,
            LocalDateTime confirmedAt,
            int version) {}

    /**
     * 不可变正文修订的读视图。
     */
    record Revision(long id, long documentId, int revision, String content, long createdBy, LocalDateTime createdAt) {}

    /**
     * 实际治理对象及修订的固定归属。
     */
    record Reference(
            int sectionIndex,
            long sourceId,
            long objectId,
            int generation,
            String kind,
            long targetId,
            long factRevisionId,
            long structureRevisionId,
            boolean selected,
            String label) {}

    /**
     * 用实际来源及关键词稳定分页。
     */
    @Select(
            DOCUMENT
                    + """
            WHERE source_id=#{source} AND (#{keyword} IS NULL OR INSTR(title,#{keyword}) > 0)
            ORDER BY id DESC
            """)
    Page<Document> list(Page<Document> page, @Param("source") long source, @Param("keyword") String keyword);

    /**
     * 标准题维护者只需要同源已确认文档作为选项。
     */
    @Select(DOCUMENT + " WHERE source_id=#{source} AND confirmed_revision_id IS NOT NULL ORDER BY id DESC")
    Page<Document> confirmed(Page<Document> page, @Param("source") long source);

    /**
     * 同源边界同时约束文档身份。
     */
    @Select(DOCUMENT + " WHERE source_id=#{source} AND id=#{id}")
    Document get(@Param("source") long source, @Param("id") long id);

    /**
     * 取明示正文，禁止拼接其他文档的历史修订。
     */
    @Select(
            """
            SELECT id,document_id,revision,content,created_by,created_at FROM kn_document_revision
            WHERE document_id=#{document} AND id=#{revision}
            """)
    Revision revision(@Param("document") long document, @Param("revision") long revision);

    /**
     * 修订列表只读取显示所需字段。
     */
    @Select(
            """
            SELECT id,document_id,revision,NULL AS content,created_by,created_at
            FROM kn_document_revision WHERE document_id=#{document} ORDER BY revision DESC
            """)
    List<Revision> history(long document);

    /**
     * 完整读取本次正文的固定引用。
     */
    @Select(
            """
            SELECT section_index,source_id,object_id,generation,kind,target_id,fact_revision_id,
                   structure_revision_id,selected,label
            FROM kn_document_source_ref WHERE revision_id=#{revision}
            ORDER BY section_index,selected DESC,kind,target_id
            """)
    List<Reference> references(long revision);

    /**
     * 在同一事务准备文档稳定身份和首份正文。
     */
    @Insert(
            """
            INSERT INTO kn_document (id,source_id,title,head_revision_id) VALUES (#{id},#{source},#{title},#{revision})
            """)
    void create(
            @Param("id") long id,
            @Param("source") long source,
            @Param("title") String title,
            @Param("revision") long revision);

    /**
     * 追加实际正文，历史修订保持可读。
     */
    @Insert(
            """
            INSERT INTO kn_document_revision (id,document_id,revision,content,created_by)
            VALUES (#{id},#{document},#{revision},#{content},#{actor})
            """)
    void append(
            @Param("id") long id,
            @Param("document") long document,
            @Param("revision") int revision,
            @Param("content") String content,
            @Param("actor") long actor);

    /**
     * 固定章节及当前已读的精确修订。
     */
    @Insert(
            """
            INSERT INTO kn_document_source_ref (revision_id,section_index,source_id,object_id,generation,kind,
                target_id,fact_revision_id,structure_revision_id,selected,label)
            VALUES (#{revision},#{ref.sectionIndex},#{ref.sourceId},#{ref.objectId},#{ref.generation},
                #{ref.kind},#{ref.targetId},#{ref.factRevisionId},#{ref.structureRevisionId},#{ref.selected},#{ref.label})
            """)
    void reference(@Param("revision") long revision, @Param("ref") Reference ref);

    /**
     * 用并发版本推进草稿指针，保留原确认。
     */
    @Update(
            """
            UPDATE kn_document SET title=#{title},head_revision_id=#{revision},version=version+1,
                updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND source_id=#{source} AND version=#{expected}
            """)
    int advance(
            @Param("source") long source,
            @Param("id") long id,
            @Param("expected") int expected,
            @Param("title") String title,
            @Param("revision") long revision);

    /**
     * 只在版本与用户实际阅读的正文一致时确认。
     */
    @Update(
            """
            UPDATE kn_document SET confirmed_revision_id=#{revision},confirmed_by=#{actor},
                confirmed_at=UTC_TIMESTAMP(3),version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{id} AND source_id=#{source} AND version=#{expected} AND head_revision_id=#{revision}
            """)
    int confirm(
            @Param("source") long source,
            @Param("id") long id,
            @Param("expected") int expected,
            @Param("revision") long revision,
            @Param("actor") long actor);

    /**
     * 保存确切审核事实，后续确认不覆盖前一份确认。
     */
    @Insert(
            """
            INSERT INTO kn_document_confirmation (document_id,revision_id,confirmed_by)
            VALUES (#{document},#{revision},#{actor})
            """)
    void recordConfirmation(
            @Param("document") long document, @Param("revision") long revision, @Param("actor") long actor);
}
