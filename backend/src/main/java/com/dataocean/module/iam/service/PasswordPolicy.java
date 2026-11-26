package com.dataocean.module.iam.service;

import com.dataocean.common.exception.BizIllegalException;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 创建、管理员重置和本人改密使用同一规则，密码不做 trim 或大小写转换。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PasswordPolicy {

    /**
     * 按统一字符数、字母及数字规则校验密码，不做 trim 或截断。
     */
    public static void requireStrong(String password) {
        if (password == null
                || password.codePointCount(0, password.length()) < 8
                || password.codePointCount(0, password.length()) > 32
                || !password.matches("(?s).*[A-Za-z].*")
                || !password.matches("(?s).*[0-9].*")
                || password.codePoints().anyMatch(Character::isISOControl)) {
            throw BizIllegalException.invalid("密码需为 8–32 位，包含字母和数字");
        }
    }
}
