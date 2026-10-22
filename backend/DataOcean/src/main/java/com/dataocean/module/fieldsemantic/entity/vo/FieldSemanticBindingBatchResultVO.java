package com.dataocean.module.fieldsemantic.entity.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 批量来源确认按字段返回结果，允许部分成功。 */
@Data
public class FieldSemanticBindingBatchResultVO {
    private Long snapshotId;
    private int totalCount;
    private int successCount;
    private int failedCount;
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item {
        private Long columnMetaId;
        private boolean success;
        private String message;
    }
}
