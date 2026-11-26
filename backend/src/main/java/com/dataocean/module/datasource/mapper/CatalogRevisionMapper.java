package com.dataocean.module.datasource.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import lombok.Builder;
import lombok.Data;

/**
 * 写入不可变对象、字段修订及其复合主键成员。
 */
public interface CatalogRevisionMapper {

    /**
     * 在来源事务内读取已提交或已写入的最后一个对象修订序号。
     */
    @Select("SELECT COALESCE(MAX(revision),0) FROM ds_object_revision WHERE object_id=#{object}")
    int latestObjectVersion(@Param("object") long object);

    /**
     * 创建对象修订并回填编号，创建时间来自数据库时钟。
     */
    @Insert(
            """
            INSERT INTO ds_object_revision (object_id,revision,namespace,source_name,object_type,source_comment,
                view_facts,definition_digest,created_at)
            VALUES (#{objectId},#{revision},#{namespace},#{sourceName},#{objectType},#{sourceComment},
                #{viewFacts},#{definitionDigest},UTC_TIMESTAMP(3))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertObjectRevision(ObjectRevision row);

    /**
     * 读取上次对象修订的字段身份和修订。
     */
    @Select(
            """
            SELECT c.source_name,c.column_id,c.id AS revision_id,c.revision AS version FROM ds_object_column f
            JOIN ds_column_revision c ON c.id=f.column_revision_id WHERE f.object_revision_id=#{revision}
            """)
    List<ReferencedColumn> columns(@Param("revision") long revision);

    /**
     * 创建变化后的字段修订并回填主键。
     */
    @Insert(
            """
            INSERT INTO ds_column_revision (column_id,revision,source_name,ordinal,native_type,nullable,
                default_expression,generated_expression,source_comment,created_at)
            VALUES (#{columnId},#{revision},#{sourceName},#{ordinal},#{nativeType},#{nullable},
                #{defaultExpression},#{generatedExpression},#{sourceComment},UTC_TIMESTAMP(3))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertColumnRevision(ColumnRevision row);

    /**
     * 保存对象修订的字段成员及来源顺序。
     */
    @Insert(
            """
            INSERT INTO ds_object_column (object_revision_id,column_revision_id,ordinal)
            VALUES (#{object},#{column},#{ordinal})
            """)
    int addColumn(@Param("object") long object, @Param("column") long column, @Param("ordinal") int ordinal);

    /**
     * 上次对象修订的字段引用。
     *
     * @param sourceName 来源字段名
     * @param columnId 稳定字段编号
     * @param revisionId 字段修订编号
     * @param version 字段修订序号
     */
    record ReferencedColumn(String sourceName, long columnId, long revisionId, int version) {}

    /**
     * 新对象修订的 SQL 参数及数据库回填编号。
     */
    @Data
    @Builder
    class ObjectRevision {

        /**
         * 数据库生成的修订编号。
         */
        private Long id;

        /**
         * 同一身份下的修订序号。
         */
        private int revision;

        /**
         * 来源中的原始名称。
         */
        private String sourceName;

        /**
         * 所属稳定对象编号。
         */
        private long objectId;

        /**
         * 来源限定 Schema。
         */
        private String namespace;

        /**
         * 源对象类型。
         */
        private String objectType;

        /**
         * 从源库读取的对象注释。
         */
        private String sourceComment;

        /**
         * 所依赖来源对象的 JSON 引用。
         */
        private String viewFacts;

        /**
         * 当前对象结构摘要。
         */
        private byte[] definitionDigest;
    }

    /**
     * 新字段修订的 SQL 参数及数据库回填编号。
     */
    @Data
    @Builder
    class ColumnRevision {

        /**
         * 数据库生成的修订编号。
         */
        private Long id;

        /**
         * 同一身份下的修订序号。
         */
        private int revision;

        /**
         * 来源中的原始名称。
         */
        private String sourceName;

        /**
         * 所属稳定字段编号。
         */
        private long columnId;

        /**
         * 字段在源对象内的顺序。
         */
        private int ordinal;

        /**
         * 源库原生字段类型。
         */
        private String nativeType;

        /**
         * 来源字段是否允许空值。
         */
        private boolean nullable;

        /**
         * 从源库读取的默认表达式。
         */
        private String defaultExpression;

        /**
         * 源字段为生成列时记录生成标记。
         */
        private String generatedExpression;

        /**
         * 从源库读取的字段注释。
         */
        private String sourceComment;
    }
}
