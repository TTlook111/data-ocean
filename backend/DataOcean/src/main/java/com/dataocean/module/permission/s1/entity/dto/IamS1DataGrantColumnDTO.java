package com.dataocean.module.permission.s1.entity.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 授权中明确选择的一列。 */
@Getter
@Setter
@NoArgsConstructor
public class IamS1DataGrantColumnDTO {
    private Long columnMetaId;
    private String columnName;

    public IamS1DataGrantColumnDTO(Long columnMetaId, String columnName) {
        this.columnMetaId = columnMetaId;
        this.columnName = columnName;
    }
}
