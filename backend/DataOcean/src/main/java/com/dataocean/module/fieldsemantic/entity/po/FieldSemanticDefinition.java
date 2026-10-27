package com.dataocean.module.fieldsemantic.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 稳定字段身份对应的权威语义主记录。 */
@Data
@TableName("field_semantic_definition")
public class FieldSemanticDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasourceId;
    private String databaseName;
    private String tableName;
    private String columnName;
    private String identityHash;
    private Long currentBindingId;
    private Long currentApprovedRevisionId;
    private Long workingRevisionId;
    private String status;
    private Integer rowVersion;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
