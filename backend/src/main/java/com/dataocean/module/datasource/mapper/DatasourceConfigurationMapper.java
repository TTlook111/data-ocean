package com.dataocean.module.datasource.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import lombok.Data;

/**
 * 保存不可变连接配置修订及其复合键 Schema 成员。
 */
public interface DatasourceConfigurationMapper {

    /**
     * 单条和批量回读共用的连接修订投影。
     */
    String REVISION_SELECTION =
            """
            SELECT r.id,r.source_id,r.revision,s.name AS source_label,r.database_type,r.host,r.port,r.database_name,
                r.account,r.credential_revision_id,r.purpose,CAST(r.technical_contact_id AS CHAR) AS technical_contact_id,
                CAST(r.business_contact_id AS CHAR) AS business_contact_id,r.max_connections,r.collection_concurrency,
                r.retry_limit,r.task_timeout_seconds,r.query_timeout_seconds,r.row_limit,r.sync_hours,
                r.window_start_minute,r.window_end_minute,r.scope_key,r.created_by
            FROM ds_connection_revision r JOIN ds_source s ON s.id=r.source_id
            """;

    /**
     * 读取指定来源下的修订及当前源名称。
     */
    @Select(REVISION_SELECTION + "WHERE r.source_id=#{source} AND r.id=#{revision}")
    RevisionData revision(@Param("source") long source, @Param("revision") long revision);

    /**
     * 按同一来源和本页所引用的非空配置编号批量读取修订。
     */
    @Select(
            "<script>"
                    + REVISION_SELECTION
                    + """
                      WHERE r.source_id=#{source} AND r.id IN
                        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
                      </script>
                      """)
    List<RevisionData> revisions(@Param("source") long source, @Param("ids") List<Long> ids);

    /**
     * 按原顺序读取所属来源的配置 Schema。
     */
    @Select(
            """
            SELECT x.schema_name FROM ds_connection_schema x
            JOIN ds_connection_revision r ON r.id=x.connection_revision_id
            WHERE r.source_id=#{source} AND r.id=#{revision} ORDER BY x.ordinal
            """)
    List<String> schemas(@Param("source") long source, @Param("revision") long revision);

    /**
     * 批量读取本次修订的 Schema，按配置内原有顺序排列。
     */
    @Select(
            """
            <script>
              SELECT x.connection_revision_id AS revisionId, x.schema_name AS schemaName
              FROM ds_connection_schema x
              JOIN ds_connection_revision r ON r.id=x.connection_revision_id
              WHERE r.source_id=#{source} AND r.id IN
                <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
              ORDER BY r.id, x.ordinal
            </script>
            """)
    List<RevisionSchema> revisionSchemas(@Param("source") long source, @Param("ids") List<Long> ids);

    /**
     * 批量读取配置时的 Schema 投影。
     *
     * @param revisionId Schema 所属连接修订编号
     * @param schemaName 配置保存的原始 Schema 名称
     */
    record RevisionSchema(long revisionId, String schemaName) {}

    /**
     * 写入固定配置并用数据库时钟生成创建时间，回填修订编号。
     */
    @Insert(
            """
            INSERT INTO ds_connection_revision (source_id,revision,database_type,host,port,database_name,account,
                credential_revision_id,purpose,technical_contact_id,business_contact_id,max_connections,collection_concurrency,
                retry_limit,task_timeout_seconds,query_timeout_seconds,row_limit,sync_hours,window_start_minute,
                window_end_minute,scope_key,created_by,created_at)
            VALUES (#{sourceId},#{revision},#{databaseType},#{host},#{port},#{databaseName},#{account},
                #{credentialRevisionId},#{purpose},#{technicalContactId},#{businessContactId},#{maxConnections},
                #{collectionConcurrency},#{retryLimit},#{taskTimeoutSeconds},#{queryTimeoutSeconds},#{rowLimit},#{syncHours},
                #{windowStartMinute},#{windowEndMinute},#{scopeKey},#{createdBy},UTC_TIMESTAMP(3))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int create(RevisionData row);

    /**
     * 在同一配置修订下保存原 Schema 名、字节身份和显示顺序。
     */
    @Insert(
            """
            INSERT INTO ds_connection_schema (connection_revision_id,schema_name,schema_key,ordinal)
            VALUES (#{revision},#{name},#{key},#{ordinal})
            """)
    int addSchema(
            @Param("revision") long revision,
            @Param("name") String name,
            @Param("key") byte[] key,
            @Param("ordinal") int ordinal);

    /**
     * 配置修订的 SQL 读写数据，包含详情所需的来源名称投影。
     */
    @Data
    class RevisionData {

        /**
         * 配置修订编号，由数据库生成并回填。
         */
        private Long id;

        /**
         * 本修订所属的数据源。
         */
        private long sourceId;

        /**
         * 数据源内部的修订序号。
         */
        private int revision;

        /**
         * 关联数据源的当前展示名称，仅供读取投影。
         */
        private String sourceLabel;

        /**
         * 数据库适配器类型。
         */
        private String databaseType;

        /**
         * 经过连接策略核验的主机名。
         */
        private String host;

        /**
         * 源数据库连接端口。
         */
        private Integer port;

        /**
         * 源数据库名称。
         */
        private String databaseName;

        /**
         * 执行平台只读访问的账号名。
         */
        private String account;

        /**
         * 本修订固定使用的凭据版本。
         */
        private Long credentialRevisionId;

        /**
         * 业务用途说明。
         */
        private String purpose;

        /**
         * 技术联系人编号，使用十进制文本。
         */
        private String technicalContactId;

        /**
         * 业务联系人编号，使用十进制文本。
         */
        private String businessContactId;

        /**
         * 连接数上限。
         */
        private Integer maxConnections;

        /**
         * 采集并发上限。
         */
        private Integer collectionConcurrency;

        /**
         * 允许的重试次数。
         */
        private Integer retryLimit;

        /**
         * 采集执行期限，单位为秒。
         */
        private Integer taskTimeoutSeconds;

        /**
         * 单次查询期限，单位为秒。
         */
        private Integer queryTimeoutSeconds;

        /**
         * 只读查询的最大返回行数。
         */
        private Integer rowLimit;

        /**
         * 定时采集周期，单位为小时。
         */
        private Integer syncHours;

        /**
         * 开始时点距业务日零点的分钟数。
         */
        private int windowStartMinute;

        /**
         * 结束时点距业务日零点的分钟数。
         */
        private int windowEndMinute;

        /**
         * 配置经核验的固定采集范围摘要。
         */
        private byte[] scopeKey;

        /**
         * 创建此修订的操作主体编号。
         */
        private Long createdBy;
    }
}
