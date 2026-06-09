package com.dataocean.common.util;

/**
 * 数字解析工具类。
 * <p>
 * 提供安全的数字解析方法，避免 NumberFormatException。
 * </p>
 */
public final class NumberUtils {

    private NumberUtils() {
        // 工具类不允许实例化
    }

    /**
     * 安全解析整数。
     *
     * @param value        字符串值
     * @param defaultValue 默认值
     * @return 解析后的整数，解析失败返回默认值
     */
    public static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 安全解析长整数。
     *
     * @param value        字符串值
     * @param defaultValue 默认值
     * @return 解析后的长整数，解析失败返回默认值
     */
    public static long parseLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 安全解析浮点数。
     *
     * @param value        字符串值
     * @param defaultValue 默认值
     * @return 解析后的浮点数，解析失败返回默认值
     */
    public static double parseDouble(String value, double defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
