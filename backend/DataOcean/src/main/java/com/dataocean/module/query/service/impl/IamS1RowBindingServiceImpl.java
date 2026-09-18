package com.dataocean.module.query.service.impl;

import com.dataocean.module.permission.s1.entity.IamS1RowCondition;
import com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot;
import com.dataocean.module.permission.s1.entity.vo.IamS1GrantSourceVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1RowPredicateVO;
import com.dataocean.module.permission.s1.mapper.IamS1RowConditionMapper;
import com.dataocean.module.query.entity.dto.IamS1ExecutionBinding;
import com.dataocean.module.query.service.IamS1RowBindingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 绑定只在当前 Java 调用栈内存在。结构化值来自 S1 row condition，
 * parameterReference 没有可证明的服务端值来源时直接拒绝。
 */
@Service
@RequiredArgsConstructor
public class IamS1RowBindingServiceImpl implements IamS1RowBindingService {

    private final IamS1RowConditionMapper rowConditionMapper;
    private final ObjectMapper objectMapper;

    @Override
    public List<IamS1ExecutionBinding> build(IamS1DataAuthorizationSnapshot snapshot) {
        if (snapshot == null || snapshot.getTables() == null) {
            throw new IllegalStateException("S1 执行快照缺失");
        }
        Map<Long, IamS1RowCondition> byId = new HashMap<>();
        List<Long> grantIds = snapshot.getTables().stream()
                .flatMap(table -> table.getGrantSources().stream())
                .map(IamS1GrantSourceVO::getGrantId)
                .filter(java.util.Objects::nonNull)
                .distinct().toList();
        if (!grantIds.isEmpty()) {
            List<IamS1RowCondition> rows = rowConditionMapper.selectByGrantIds(grantIds);
            if (rows == null) {
                throw new IllegalStateException("S1 记录条件读取失败");
            }
            rows.stream().filter(row -> row.getId() != null).forEach(row -> byId.put(row.getId(), row));
        }

        Map<String, IamS1ExecutionBinding> bindings = new LinkedHashMap<>();
        for (var table : snapshot.getTables()) {
            for (IamS1GrantSourceVO source : table.getGrantSources()) {
                if (source.getRowCondition() == null) {
                    continue;
                }
                for (IamS1RowPredicateVO predicate : source.getRowCondition().getPredicates()) {
                    String reference = predicate.getBindingReference();
                    if (reference == null || reference.isBlank()) {
                        throw new IllegalStateException("S1 记录条件绑定引用缺失");
                    }
                    String idPart = reference.substring(reference.lastIndexOf('-') + 1);
                    IamS1RowCondition row;
                    try {
                        row = byId.get(Long.valueOf(idPart));
                    } catch (NumberFormatException ex) {
                        row = null;
                    }
                    if (row == null || row.getStructuredValueJson() == null
                            || row.getStructuredValueJson().isBlank()) {
                        throw new IllegalStateException("S1 记录条件没有可验证的服务端绑定");
                    }
                    try {
                        JsonNode node = objectMapper.readTree(row.getStructuredValueJson());
                        Object value = objectMapper.treeToValue(node, Object.class);
                        bindings.putIfAbsent(reference,
                                new IamS1ExecutionBinding(reference, row.getValueType(), value));
                    } catch (Exception ex) {
                        throw new IllegalStateException("S1 记录条件绑定解析失败");
                    }
                }
            }
        }
        return new ArrayList<>(bindings.values());
    }
}
