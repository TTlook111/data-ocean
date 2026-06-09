package com.dataocean.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 哈希工具类。
 * <p>
 * 提供常用的哈希算法实现，如 MD5、SHA-256 等。
 * </p>
 */
public final class HashUtils {

    private HashUtils() {
        // 工具类不允许实例化
    }

    /**
     * 计算字符串的 MD5 值。
     *
     * @param input 输入字符串
     * @return 32 位小写十六进制 MD5 值，异常时返回空字符串
     */
    public static String md5Hex(String input) {
        if (input == null) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 计算字符串的 SHA-256 值。
     *
     * @param input 输入字符串
     * @return 64 位小写十六进制 SHA-256 值，异常时返回空字符串
     */
    public static String sha256Hex(String input) {
        if (input == null) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
