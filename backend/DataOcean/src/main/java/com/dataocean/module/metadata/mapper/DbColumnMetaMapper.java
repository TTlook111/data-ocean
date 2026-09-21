package com.dataocean.module.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 数据库字段元数据 Mapper 接口。
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供字段元数据的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface DbColumnMetaMapper extends BaseMapper<DbColumnMeta> {

    /**
     * 按快照 + 表 + 列锁定字段元数据行（`SELECT ... FOR UPDATE`）。
     *
     * <p>掩码候选确认用它做并发串行化：`iam_s1_field_protection` 没有唯一约束，
     * 两个请求同时确认同一字段时，若不先取得同一把行锁，会各自读到“没有 ACTIVE 同策略保护”
     * 并各插一条，顺序调用幂等但并发不幂等。</p>
     *
     * <p>必须在事务内调用，否则行锁立即释放。</p>
     */
    @Select("""
            SELECT * FROM db_column_meta
            WHERE snapshot_id = #{snapshotId}
              AND table_name = #{tableName}
              AND column_name = #{columnName}
            LIMIT 1
            FOR UPDATE
            """)
    DbColumnMeta selectForUpdate(@Param("snapshotId") Long snapshotId,
                                 @Param("tableName") String tableName,
                                 @Param("columnName") String columnName);
}
