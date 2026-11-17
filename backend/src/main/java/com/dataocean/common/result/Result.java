package com.dataocean.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 复用项目基础代码的统一返回，HTTP 状态与具体业务错误码分别表达。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {
    private int code;
    private String message;
    private T data;

    public Result(T data) { this(200, "success", data); }
    public Result(int code, String message) { this(code, message, null); }
    public static <T> Result<T> success(T data) { return new Result<>(data); }
    public static <T> Result<T> fail(String message) { return new Result<>(500001, message); }
    public static <T> Result<T> fail(int code, String message) { return new Result<>(code, message); }
}
