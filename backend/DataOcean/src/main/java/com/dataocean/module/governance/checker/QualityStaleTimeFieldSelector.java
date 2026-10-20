package com.dataocean.module.governance.checker;

import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.entity.po.DbTableMeta;

import java.util.List;

/** 统一规划器与数据检查器选择时效检查字段的规则，并在选择前执行治理状态过滤。 */
public final class QualityStaleTimeFieldSelector {
    private static final List<String> CANDIDATES =
            List.of("update_time", "updated_at", "modify_time", "modified_at", "gmt_modified");

    private QualityStaleTimeFieldSelector() {}

    public static String select(DbTableMeta table, List<DbColumnMeta> columns) {
        if (table == null || restricted(table.getGovernanceStatus())) return null;
        for (String candidate : CANDIDATES) {
            for (DbColumnMeta column : columns) {
                if (column.getTableName() != null && column.getTableName().equalsIgnoreCase(table.getTableName())
                        && candidate.equalsIgnoreCase(column.getColumnName())
                        && !restricted(column.getGovernanceStatus())) {
                    return column.getColumnName();
                }
            }
        }
        return null;
    }

    public static boolean restricted(String governanceStatus) {
        return "BLOCKED".equalsIgnoreCase(governanceStatus)
                || "DEPRECATED".equalsIgnoreCase(governanceStatus);
    }
}
