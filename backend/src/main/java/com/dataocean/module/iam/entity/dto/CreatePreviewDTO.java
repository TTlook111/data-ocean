package com.dataocean.module.iam.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 人员、角色和部门创建前共用的资料核对包装，具体非秘密资料类型由接口声明。
 *
 * @param <T> 当前创建入口允许接收的非秘密资料类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class CreatePreviewDTO<T> {

    /**
     * 当前资源的完整创建资料，沿用具体类型的字段与校验约束。
     */
    @NotNull
    @Valid
    private T proposal;
}
