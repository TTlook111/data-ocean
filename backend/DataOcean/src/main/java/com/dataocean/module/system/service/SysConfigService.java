package com.dataocean.module.system.service;

import java.util.Map;

public interface SysConfigService {

    String getValue(String key);

    String getValue(String key, String defaultValue);

    void setValue(String key, String value);

    Map<String, String> getByPrefix(String prefix);
}
