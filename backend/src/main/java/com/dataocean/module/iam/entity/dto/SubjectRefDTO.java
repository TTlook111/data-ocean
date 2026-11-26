package com.dataocean.module.iam.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公共契约的明确传输结构，字段职责及校验与接口契约一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubjectRefDTO {

    /**
     * 当前引用或统计对象的业务类别。
     */
    private String kind;

    /**
     * 该记录的稳定标识，对外长整型以十进制字符串表达。
     */
    private String id;
}
