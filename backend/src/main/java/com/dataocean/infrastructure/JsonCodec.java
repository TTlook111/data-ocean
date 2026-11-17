package com.dataocean.infrastructure;

import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import lombok.RequiredArgsConstructor;

/**
 * 通过应用统一 JSON 配置处理已声明类型，拒绝悄悄接纳未知字段。
 */
@Component
@RequiredArgsConstructor
public class JsonCodec {

    /**
     * 统一的明确类型 JSON 编解码适配。
     */
    private final JsonMapper json;

    /**
     * 写出统一结构或编码明确类型数据，I/O 失败由允许处理的边界承接。
     */
    public String write(Object value) {
        return json.writeValueAsString(value);
    }

    /**
     * 按明确目标类型读取受控 JSON，不以任意 Map 绕过业务结构。
     */
    public <T> T read(String value, Class<T> type) {
        return json.readValue(value, type);
    }

    /**
     * 按明确字符串集合类型读取受控角色快照。
     */
    public List<String> strings(String value) {
        return json.readValue(value, new TypeReference<List<String>>() {});
    }

    /**
     * 提供同一份已配置 JSON 映射器用于明确节点规范化。
     */
    public JsonMapper mapper() {
        return json;
    }
}
