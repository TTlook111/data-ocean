package com.dataocean.module.system.service;

import java.util.Map;

/**
 * 系统配置服务接口。
 * <p>
 * 提供对 sys_config 表的业务操作，包括按 key 读取配置值、
 * 设置配置值（不存在则新增，存在则更新）、按前缀批量获取配置等。
 * </p>
 *
 * @author dataocean
 */
public interface SysConfigService {

    /**
     * 根据配置键获取配置值。
     *
     * @param key 配置键
     * @return 配置值；若不存在返回 null
     */
    String getValue(String key);

    /**
     * 根据配置键获取配置值，若不存在则返回默认值。
     *
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 配置值；若不存在返回 defaultValue
     */
    String getValue(String key, String defaultValue);

    /**
     * 设置配置值（不存在则新增，存在则更新）。
     *
     * @param key   配置键
     * @param value 配置值
     */
    void setValue(String key, String value);

    /**
     * 根据配置键前缀批量获取配置。
     *
     * @param prefix 配置键前缀（如 "security."）
     * @return key-value 映射；若无匹配返回空 Map
     */
    Map<String, String> getByPrefix(String prefix);
}
