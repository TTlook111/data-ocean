package com.dataocean.common.exception;

import com.dataocean.common.ErrorVO.FieldErrorVO;

import java.util.List;

import lombok.Getter;

/**
 * 受业务状态决定的字段错误，保留可定位的安全字段路径。
 */
@Getter
public final class FieldValidationException extends BizIllegalException {

    /**
     * 本次失败涉及的明确业务字段。
     */
    private final List<FieldErrorVO> fields;

    /**
     * 给业务确认门槛提供与 Bean 校验相同的字段错误格式。
     */
    public FieldValidationException(List<FieldErrorVO> fields) {
        super("请检查填写内容");
        this.fields = List.copyOf(fields);
    }
}
