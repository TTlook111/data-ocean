package com.dataocean.infrastructure;

import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JsonCodec {
    private final JsonMapper json;
    public JsonCodec(JsonMapper json) { this.json = json; }
    public String write(Object value) { return json.writeValueAsString(value); }
    public <T> T read(String value, Class<T> type) { return json.readValue(value, type); }
    public List<String> strings(String value) { return json.readValue(value, new TypeReference<List<String>>() {}); }
    public JsonMapper mapper() { return json; }
}
