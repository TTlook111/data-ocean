package com.dataocean.module.metadata.entity.vo;

import lombok.Data;

import java.util.List;

@Data
public class SchemaDiffVO {

    private List<String> addedTables;
    private List<String> removedTables;
    private List<ColumnChange> addedColumns;
    private List<ColumnChange> removedColumns;
    private List<ColumnChange> modifiedColumns;

    @Data
    public static class ColumnChange {
        private String tableName;
        private String columnName;
        private String oldType;
        private String newType;
        private String oldComment;
        private String newComment;
    }
}
