package com.dataocean.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应结果封装类
 * <p>
 * 所有 API 接口统一返回此格式，包含状态码、消息和数据体，
 * 便于前端统一解析处理。
 * </p>
 *
 * @param <T> 响应数据的类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /** 响应状态码（200 表示成功，其他表示错误） */
    private Integer code;

    /** 响应消息描述 */
    private String message;

    /** 响应数据体 */
    private T data;

    /**
     * 返回成功结果（无数据）
     *
     * @param <T> 数据类型
     * @return 成功的 Result 对象
     */
    public static <T> Result<T> success() {
        return new Result<>(200, "success", null);
    }

    /**
     * 返回成功结果（携带数据）
     *
     * @param data 响应数据
     * @param <T>  数据类型
     * @return 成功的 Result 对象
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data);
    }

    /**
     * 返回成功结果（自定义消息 + 数据）
     *
     * @param message 自定义成功消息
     * @param data    响应数据
     * @param <T>     数据类型
     * @return 成功的 Result 对象
     */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(200, message, data);
    }

    /**
     * 返回错误结果
     *
     * @param code    错误码
     * @param message 错误描述
     * @param <T>     数据类型
     * @return 错误的 Result 对象
     */
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }
}
