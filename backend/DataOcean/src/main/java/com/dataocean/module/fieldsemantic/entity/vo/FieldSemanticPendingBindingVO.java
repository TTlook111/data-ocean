package com.dataocean.module.fieldsemantic.entity.vo;

import lombok.Data;

/** 待确认来源绑定的可核验摘要。 */
@Data
public class FieldSemanticPendingBindingVO {
    private Long columnMetaId;
    private Integer expectedVersion;
    private String tableName;
    private String columnName;
    private String businessName;
    private boolean eligible;
    private String reason;
}
