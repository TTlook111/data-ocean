package com.dataocean.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.system.entity.SysConfig;
import com.dataocean.module.system.mapper.SysConfigMapper;
import com.dataocean.module.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统配置服务实现类。
 * <p>
 * 基于 MyBatis-Plus 操作 sys_config 表，实现配置的读取和写入。
 * 写入操作采用"存在则更新、不存在则新增"的 upsert 语义。
 * </p>
 *
 * @author dataocean
 */
@Service
@RequiredArgsConstructor
public class SysConfigServiceImpl implements SysConfigService {

    /** 系统配置 Mapper */
    private final SysConfigMapper configMapper;

    /**
     * {@inheritDoc}
     */
    @Override
    public String getValue(String key) {
        // 委托给带默认值的重载方法，默认值为 null
        return getValue(key, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getValue(String key, String defaultValue) {
        // 按 configKey 精确查询配置记录
        SysConfig config = configMapper.selectOne(
                new LambdaQueryWrapper<SysConfig>()
                        .eq(SysConfig::getConfigKey, key)
        );
        // 未找到记录时返回调用方指定的默认值
        if (config == null) {
            return defaultValue;
        }
        return config.getConfigValue();
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void setValue(String key, String value) {
        // 先查询是否已存在该配置键
        SysConfig config = configMapper.selectOne(
                new LambdaQueryWrapper<SysConfig>()
                        .eq(SysConfig::getConfigKey, key)
        );
        if (config == null) {
            // 不存在则新增一条配置记录
            config = new SysConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            configMapper.insert(config);
        } else {
            // 已存在则更新配置值
            config.setConfigValue(value);
            configMapper.updateById(config);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, String> getByPrefix(String prefix) {
        // 使用 likeRight 实现前缀匹配（等价于 LIKE 'prefix%'）
        List<SysConfig> configs = configMapper.selectList(
                new LambdaQueryWrapper<SysConfig>()
                        .likeRight(SysConfig::getConfigKey, prefix)
        );
        // 将查询结果转换为 key-value Map
        Map<String, String> result = new HashMap<>();
        for (SysConfig config : configs) {
            result.put(config.getConfigKey(), config.getConfigValue());
        }
        return result;
    }
}
