package com.dataocean.module.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 知识文档版本 Mapper 接口。
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供知识文档版本表的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface KnowledgeDocVersionMapper extends BaseMapper<KnowledgeDocVersion> {

    /**
     * 读取某文档某个版本的数据源归属与来源快照。
     *
     * <p>资源解析器用它校验「文档的 datasourceId 与版本/来源快照归属一致」。
     * 只取归属列，不把文档正文读出来——解析器只承载归属事实。</p>
     */
    @Select("""
            SELECT id, doc_id, datasource_id, metadata_snapshot_id, version_no
            FROM knowledge_doc_version WHERE doc_id = #{docId} AND version_no = #{versionNo} LIMIT 1
            """)
    KnowledgeDocVersion selectOwnershipByDocAndVersionNo(@Param("docId") Long docId,
                                                         @Param("versionNo") Integer versionNo);
}
