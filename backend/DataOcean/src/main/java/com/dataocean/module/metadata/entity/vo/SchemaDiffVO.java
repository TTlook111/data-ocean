package com.dataocean.module.metadata.entity.vo;

import lombok.Data;

import java.util.List;

/**
 * Schema 差异对比视图对象。
 * <p>
 * 用于展示两个快照之间的 Schema 变更，包括新增/删除的表和字段级别的变更明细。
 * </p>
 */
@Data
public class SchemaDiffVO {

    /** 新增的表名列表 */
    private List<String> addedTables;

    /** 删除的表名列表 */
    private List<String> removedTables;

    /** 新增的字段变更列表 */
    private List<ColumnChange> addedColumns;

    /** 删除的字段变更列表 */
    private List<ColumnChange> removedColumns;

    /** 修改的字段变更列表（类型或注释发生变化） */
    private List<ColumnChange> modifiedColumns;

    /**
     * 字段变更明细。
     * <p>
     * 记录单个字段的变更信息，包括所属表名、字段名、变更前后的类型和注释。
     * </p>
     */
    @Data
    public static class ColumnChange {

        /** 所属表名 */
        private String tableName;

        /** 字段名 */
        private String columnName;

        /** 变更前的数据类型 */
        private String oldType;

        /** 变更后的数据类型 */
        private String newType;

        /** 变更前的注释 */
        private String oldComment;

        /** 变更后的注释 */
        private String newComment;
    }
}
