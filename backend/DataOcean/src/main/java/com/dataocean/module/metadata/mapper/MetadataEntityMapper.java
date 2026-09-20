package com.dataocean.module.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.metadata.entity.MetadataEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 统一实体 Mapper 接口
 *
 * @author dataocean
 */
@Mapper
public interface MetadataEntityMapper extends BaseMapper<MetadataEntity> {

    /**
     * 按 FQN 精确查询实体
     */
    @Select("SELECT * FROM metadata_entity WHERE fqn = #{fqn} LIMIT 1")
    MetadataEntity selectByFqn(@Param("fqn") String fqn);

    /**
     * 按数据源 ID 查询所有 TABLE 类型实体
     * <p>
     * 通过 FQN 前缀匹配（fqn LIKE 'datasourceName.db.%'）
     * </p>
     */
    @Select("SELECT * FROM metadata_entity WHERE entity_type = 'TABLE' AND fqn LIKE CONCAT(#{fqnPrefix}, '.%')")
    List<MetadataEntity> selectTablesByFqnPrefix(@Param("fqnPrefix") String fqnPrefix);

    /**
     * 按数据源 ID 查询所有实体（通过 entity_metadata 中的 datasource_id）
     */
    @Select("SELECT * FROM metadata_entity WHERE JSON_EXTRACT(entity_metadata, '$.datasource_id') = #{datasourceId}")
    List<MetadataEntity> selectByDatasourceId(@Param("datasourceId") Long datasourceId);

    /**
     * 全文搜索实体。
     * <p>
     * datasourceId 非空时按 `entity_metadata.datasource_id` 收窄到单个数据源；
     * 为空表示全局搜索。过滤条件必须下推到 SQL，否则 LIMIT/OFFSET 会在过滤前生效，
     * 分页结果条数与实际匹配数不一致。
     * </p>
     */
    @Select("""
            <script>
            SELECT *, MATCH(name, display_name, description) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS relevance
            FROM metadata_entity
            WHERE MATCH(name, display_name, description) AGAINST(#{query} IN NATURAL LANGUAGE MODE)
            <if test="entityType != null">
              AND entity_type = #{entityType}
            </if>
            <if test="datasourceId != null">
              AND JSON_EXTRACT(entity_metadata, '$.datasource_id') = #{datasourceId}
            </if>
            ORDER BY relevance DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<MetadataEntity> fullTextSearch(@Param("query") String query,
                                         @Param("entityType") String entityType,
                                         @Param("datasourceId") Long datasourceId,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);

    /**
     * 全文搜索实体，按**多个**数据源收窄（负责源范围）。
     *
     * <p>调用方必须保证 `datasourceIds` 非空：空集合会生成 `IN ()` 这样的非法 SQL，
     * 而且“没有任何负责源”应当直接得到空结果，不应该退化成全局搜索。</p>
     */
    @Select("""
            <script>
            SELECT *, MATCH(name, display_name, description) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS relevance
            FROM metadata_entity
            WHERE MATCH(name, display_name, description) AGAINST(#{query} IN NATURAL LANGUAGE MODE)
            <if test="entityType != null">
              AND entity_type = #{entityType}
            </if>
            AND JSON_EXTRACT(entity_metadata, '$.datasource_id') IN
            <foreach collection="datasourceIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY relevance DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<MetadataEntity> fullTextSearchScoped(@Param("query") String query,
                                              @Param("entityType") String entityType,
                                              @Param("datasourceIds") Collection<Long> datasourceIds,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);

    /**
     * 按负责源范围读取指定类型的全部实体。
     *
     * <p>调用方必须保证 `datasourceIds` 非空：空集合会生成非法 SQL，
     * 且“没有任何负责源”应当直接得到空结果。</p>
     */
    @Select("""
            <script>
            SELECT * FROM metadata_entity
            WHERE entity_type = #{entityType}
              AND JSON_EXTRACT(entity_metadata, '$.datasource_id') IN
            <foreach collection="datasourceIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<MetadataEntity> selectByDatasourceIdsAndType(@Param("datasourceIds") Collection<Long> datasourceIds,
                                                      @Param("entityType") String entityType);

    /**
     * 属于这些数据源的全部实体 ID。
     *
     * <p>血缘遍历用它做节点可见性判定：血缘是跨实体的图，只校验起点会沿着边走到
     * 调用者无负责权限的数据源元数据。`datasourceIds` 为空返回空列表。</p>
     */
    @Select("""
            <script>
            SELECT id FROM metadata_entity
            WHERE JSON_EXTRACT(entity_metadata, '$.datasource_id') IN
            <foreach collection="datasourceIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> selectEntityIdsByDatasourceIds(@Param("datasourceIds") Collection<Long> datasourceIds);

    /**
     * 按实体 ID 做**当前读**并加行锁。
     *
     * <p>取得字段元数据行锁之后需要重新读取候选状态，而 MySQL 默认 REPEATABLE READ 下
     * 普通 `SELECT` 读的是事务开始时的快照——拿锁前建立的那个快照看不到等待期间其他事务的提交。
     * 所以这里必须用 `FOR UPDATE` 做当前读。</p>
     */
    @Select("SELECT * FROM metadata_entity WHERE id = #{entityId} FOR UPDATE")
    MetadataEntity selectByIdForUpdate(@Param("entityId") Long entityId);

    /** 按实体 ID 读取它所属的数据源；实体不存在或没有数据源信息时返回 null。 */
    @Select("SELECT JSON_EXTRACT(entity_metadata, '$.datasource_id') FROM metadata_entity WHERE id = #{entityId}")
    Long selectDatasourceIdByEntityId(@Param("entityId") Long entityId);
}
