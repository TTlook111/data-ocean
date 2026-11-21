package com.dataocean.module.knowledge.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 保存显式文档依赖及发布时核对过的确切绑定。
 */
public interface KnowledgeDocumentDependencyMapper {

    /**
     * 在新正文修订下固定必要引用。
     */
    @Insert(
            """
            INSERT INTO kn_document_dependency(revision_id,required_document_id)
            VALUES(#{revision},#{document})
            """)
    void add(@Param("revision") long revision, @Param("document") long document);

    /**
     * 回读实际文档入口，排序用于一致核对。
     */
    @Select(
            """
            SELECT required_document_id FROM kn_document_dependency
            WHERE revision_id=#{revision} ORDER BY required_document_id
            """)
    List<Long> requiredDocuments(long revision);

    /**
     * 发布时固定该正式版本采用的已核实依赖。
     */
    @Insert(
            """
            INSERT INTO kn_binding_dependency(binding_id,required_binding_id)
            VALUES(#{binding},#{required})
            """)
    void pin(@Param("binding") long binding, @Param("required") long required);

    /**
     * 真实取用只接受同一版本已核对的依赖组合。
     */
    @Select(
            """
            SELECT required_binding_id FROM kn_binding_dependency
            WHERE binding_id=#{binding} ORDER BY required_binding_id
            """)
    List<Long> requiredBindings(long binding);
}
