package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class ChangeVO {
    private String field;
    private String label;
    private String before;
    private String after;
}
