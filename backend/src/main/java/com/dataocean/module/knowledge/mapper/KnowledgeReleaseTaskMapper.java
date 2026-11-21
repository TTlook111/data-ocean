package com.dataocean.module.knowledge.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 管理准备任务及原任务对固定候选的关联。
 */
public interface KnowledgeReleaseTaskMapper {

    /**
     * 已关联候选时沿用候选的阶段和并发版本。
     */
    String TASK =
            """
            SELECT t.id, s.document_id, t.document_revision_id, t.candidate_id,
                   IF(t.candidate_id IS NULL, s.active_binding_id, c.base_binding_id) AS base_binding_id,
                   t.title, c.fingerprint, c.current_build_id,
                   COALESCE(c.status, IF(t.canceled, 'CANCELED', 'PREPARED')) AS status,
                   COALESCE(c.version, IF(t.canceled, 2, 1)) AS version, t.created_at
            FROM kn_release_scope s JOIN kn_release_task t ON t.scope_id=s.id
            LEFT JOIN kn_release_candidate c ON c.id=t.candidate_id
            """;

    /**
     * 单文档任务的准备范围、当前候选及页面进度投影。
     */
    record Task(
            long id,
            long documentId,
            long documentRevisionId,
            Long candidateId,
            Long baseBindingId,
            String title,
            String fingerprint,
            String currentBuildId,
            String status,
            int version,
            LocalDateTime createdAt) {}

    /**
     * 同时限定任务和来源，支持尚未冻结的记录。
     */
    @Select(TASK + " WHERE s.source_id=#{source} AND t.id=#{id}")
    Task get(@Param("source") long source, @Param("id") long id);

    /**
     * 按任务编号、文档和实际阶段查询当前来源。
     */
    @Select(
            TASK
                    + """
            WHERE s.source_id=#{source} AND (#{document} IS NULL OR s.document_id=#{document})
              AND (#{keyword}='' OR LOCATE(#{keyword},CAST(t.id AS CHAR))>0)
              AND (#{status} IS NULL OR COALESCE(c.status, IF(t.canceled, 'CANCELED', 'PREPARED'))=#{status})
            ORDER BY t.id DESC
            """)
    Page<Task> list(
            Page<Task> page,
            @Param("source") long source,
            @Param("document") Long document,
            @Param("keyword") String keyword,
            @Param("status") String status);

    /**
     * 记录准备任务和用户核对的目标修订。
     */
    @Insert(
            """
            INSERT INTO kn_release_task (id,scope_id,document_revision_id,title,created_by)
            VALUES (#{id},#{scope},#{revision},#{title},#{actor})
            """)
    void insert(
            @Param("id") long id,
            @Param("scope") long scope,
            @Param("revision") long revision,
            @Param("title") String title,
            @Param("actor") long actor);

    /**
     * 首次构建事务将固定输入关联到同一任务。
     */
    @Update(
            """
            UPDATE kn_release_task SET candidate_id=#{candidate}
            WHERE id=#{id} AND candidate_id IS NULL AND canceled=FALSE
            """)
    int attach(@Param("id") long id, @Param("candidate") long candidate);

    /**
     * 准备中的任务可以直接取消。
     */
    @Update("UPDATE kn_release_task SET canceled=TRUE WHERE id=#{id} AND candidate_id IS NULL AND canceled=FALSE")
    int cancel(@Param("id") long id);
}
