package com.dataocean.module.iam.entity.vo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公共契约的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class EntityVersionRefVO {

    /**
     * 该记录的稳定标识，对外长整型以十进制字符串表达。
     */
    @NotBlank
    @Pattern(regexp = "[1-9][0-9]{0,18}")
    private String id;

    /**
     * 该业务聚合的当前修订，写操作使用它检查并发变化。
     */
    @NotNull
    @Min(1)
    private Integer version;
}
