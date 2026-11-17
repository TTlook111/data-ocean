package com.dataocean.common;

import com.dataocean.common.exception.BizIllegalException;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 在接口边界校验十进制长整型标识，避免 JavaScript 数值精度损失。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Ids {

    /**
     * 在可信边界校验并转换十进制字符串标识，避免精度和范围错误。
     */
    public static long parse(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,18}")) {
            throw BizIllegalException.invalid("记录编号不正确");
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException error) {
            throw BizIllegalException.invalid("记录编号超出范围");
        }
    }

    /**
     * 将可空外部标识转换为服务内部长整型。
     */
    public static Long nullable(String id) {
        return id == null || id.isBlank() ? null : parse(id);
    }

    /**
     * 将内部标识转换为精确十进制字符串，避免客户端长整型精度丢失。
     */
    public static String text(Long id) {
        return id == null ? null : id.toString();
    }
}
