package com.dataocean.module.permission.entity.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 权限上下文视图对象
 * <p>
 * 传给 Python AI 服务的权限约束信息，用于 SQL AST 层强制执行权限。
 * 格式与 Python UserPermissions 模型对齐。
 * </p>
 *
 * @author dataocean
 */
@Data
public class PermissionContextVO {

    /** 允许访问的表列表 */
    private List<String> allowedTables;

    /** 禁止访问的列列表，格式: "table.column" */
    private List<String> deniedColumns;

    /** 行级过滤条件列表 */
    private List<RowFilterItem> rowFilters;

    /** 需要脱敏的列列表 */
    private List<MaskColumnItem> maskColumns;

    /** 是否允许查看生成的 SQL（合并所有维度，任一维度允许即允许） */
    private boolean canViewSql = true;

    /** 是否允许导出结果（合并所有维度，任一维度允许即允许） */
    private boolean canExport = false;

    /**
     * 行级过滤条件项
     */
    @Data
    public static class RowFilterItem {
        private String tableName;
        private String condition;

        public RowFilterItem(String tableName, String condition) {
            this.tableName = tableName;
            this.condition = condition;
        }
    }

    /**
     * 脱敏列项
     */
    @Data
    public static class MaskColumnItem {
        private String tableName;
        private String columnName;
        private String maskType;

        public MaskColumnItem(String tableName, String columnName, String maskType) {
            this.tableName = tableName;
            this.columnName = columnName;
            this.maskType = maskType;
        }
    }
}
