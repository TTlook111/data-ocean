package com.dataocean.module.iam.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公共契约的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class ChangeVO {

    /**
     * 差异或校验错误对应的明确字段路径。
     */
    private String field;

    /**
     * 面向业务人员的字段或对象名称。
     */
    private String label;

    /**
     * 本次修改前的可披露内容摘要。
     */
    private String before;

    /**
     * 本次修改后的可披露内容摘要。
     */
    private String after;
}
