package com.dataocean.module.knowledge.support;

import com.dataocean.common.exception.BusinessException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 按明确的表/字段 FQN 合同解析同源完整依赖，禁止把数据库段当作表。 */
public final class LineageDependencies {
    private LineageDependencies() {}

    public static List<String> parse(String sourceFqn, String targetFqn) {
        String[] source = parts(sourceFqn), target = parts(targetFqn);
        if (source.length != target.length || !source[0].equals(target[0]) || !source[1].equals(target[1])) {
            throw new BusinessException("血缘端点类型不一致或跨越数据源/数据库，不能作为单源知识依赖");
        }
        Set<String> dependencies = new LinkedHashSet<>();
        for (String[] endpoint : List.of(source, target)) {
            dependencies.add("table:" + endpoint[2]);
            if (endpoint.length == 4) dependencies.add("column:" + endpoint[2] + "." + endpoint[3]);
        }
        return List.copyOf(dependencies);
    }

    public static String[] parts(String fqn) {
        if (fqn == null) throw new BusinessException("血缘端点 FQN 缺失");
        String[] parts = fqn.toLowerCase(Locale.ROOT).split("\\.", -1);
        if ((parts.length != 3 && parts.length != 4) || java.util.Arrays.stream(parts)
                .anyMatch(part -> part.isBlank() || !part.equals(part.trim()))) {
            throw new BusinessException("血缘端点 FQN 不符合表或字段身份合同");
        }
        return parts;
    }
}
