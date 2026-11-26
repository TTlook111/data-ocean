package com.dataocean.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 复用项目基础代码的统一返回，HTTP 状态与具体业务错误码分别表达。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /**
     * 所属业务对象或系统契约的稳定编码，不以名称替代身份。
     */
    private int code;

    /**
     * 允许向调用者披露的业务结果说明。
     */
    private String message;

    /**
     * 本次返回的明确类型业务数据。
     */
    private T data;

    /**
     * 建立该类型所需的明确依赖或值约束。
     */
    public Result(T data) {
        this(200, "success", data);
    }

    /**
     * 建立该类型所需的明确依赖或值约束。
     */
    public Result(int code, String message) {
        this(code, message, null);
    }

    /**
     * 创建带明确泛型数据的统一成功返回。
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(data);
    }

    /**
     * 创建明确失败返回，不将业务失败包装为成功。
     */
    public static <T> Result<T> fail(String message) {
        return new Result<>(500001, message);
    }

    /**
     * 创建明确失败返回，不将业务失败包装为成功。
     */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message);
    }
}
