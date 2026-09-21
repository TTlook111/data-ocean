package com.dataocean.module.glossary.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.glossary.entity.GlossaryTerm;
import com.dataocean.module.glossary.mapper.GlossaryTermMapper;
import com.dataocean.module.glossary.service.GlossaryScopeService;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.mapper.MetadataEntityMapper;
import com.dataocean.module.metadata.mapper.MetadataRelationshipMapper;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@link GlossaryScopeService} 实现。
 *
 * <p>归属来源链：术语 →（`GLOSSARY_OF` 关系）→ 实体 →（`entity_metadata.datasource_id`）→ 数据源。
 * 读取的全部是 S1/业务只读事实，不读取旧角色、旧权限码、旧数据授权或旧缓存。</p>
 *
 * <p>查询次数与对象规模无关：一次关系查询 + 一次实体查询即可算出任意多个术语的范围状态。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlossaryScopeServiceImpl implements GlossaryScopeService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final IamS1CapabilityService capabilityService;
    private final IamS1AdminGuard adminGuard;
    private final GlossaryTermMapper glossaryTermMapper;
    private final MetadataRelationshipMapper relationshipMapper;
    private final MetadataEntityMapper entityMapper;

    @Override
    public Set<Long> visibleDatasourceIds(Long userId, String functionCode) {
        Set<Long> result = new TreeSet<>();
        for (IamS1DatasourceRefVO datasource
                : capabilityService.responsibleDatasourcesWithFunction(userId, functionCode)) {
            if (datasource.id() != null) {
                result.add(datasource.id());
            }
        }
        return result;
    }

    @Override
    public Scope termScope(Long termId) {
        if (termId == null) {
            return Scope.unbound();
        }
        return termScopes(List.of(termId)).getOrDefault(termId, Scope.unbound());
    }

    @Override
    public Map<Long, Scope> termScopes(Collection<Long> termIds) {
        Map<Long, Scope> result = new LinkedHashMap<>();
        if (termIds == null || termIds.isEmpty()) {
            return result;
        }
        Set<Long> distinctTermIds = new LinkedHashSet<>(termIds);
        distinctTermIds.remove(null);
        // 先按“未关联”兜底：只有确实没有任何关联行的术语才停留在这里。
        for (Long termId : distinctTermIds) {
            result.put(termId, Scope.unbound());
        }
        if (distinctTermIds.isEmpty()) {
            return result;
        }

        List<MetadataRelationship> relations = relationshipMapper.selectGlossaryOfRelations(distinctTermIds);
        if (relations == null || relations.isEmpty()) {
            return result;
        }
        List<Long> entityIds = new ArrayList<>();
        for (MetadataRelationship relation : relations) {
            if (relation.getTargetId() != null) {
                entityIds.add(relation.getTargetId());
            }
        }
        Map<Long, Long> entityToDatasource = entityDatasourceIds(entityIds);

        // 存在关联行即已绑定；只要有一条关联解析不出归属，整个术语就是 BROKEN——
        // 不能只丢掉那一条然后把剩下的当成“合法的已绑定集合”。
        Map<Long, Set<Long>> resolved = new LinkedHashMap<>();
        Set<Long> brokenTerms = new LinkedHashSet<>();
        for (MetadataRelationship relation : relations) {
            Long termId = relation.getSourceId();
            if (termId == null) {
                continue;
            }
            Long datasourceId = entityToDatasource.get(relation.getTargetId());
            if (datasourceId == null) {
                brokenTerms.add(termId);
                continue;
            }
            resolved.computeIfAbsent(termId, key -> new LinkedHashSet<>()).add(datasourceId);
        }
        for (Long termId : distinctTermIds) {
            if (brokenTerms.contains(termId)) {
                result.put(termId, Scope.broken());
            } else if (resolved.containsKey(termId)) {
                result.put(termId, Scope.bound(resolved.get(termId)));
            }
        }
        return result;
    }

    @Override
    public Scope glossaryScope(Long glossaryId) {
        if (glossaryId == null) {
            return Scope.unbound();
        }
        List<GlossaryTerm> terms = glossaryTermMapper.selectList(
                new LambdaQueryWrapper<GlossaryTerm>()
                        .select(GlossaryTerm::getId)
                        .eq(GlossaryTerm::getGlossaryId, glossaryId));
        if (terms == null || terms.isEmpty()) {
            return Scope.unbound();
        }
        Map<Long, Scope> byTerm = termScopes(terms.stream().map(GlossaryTerm::getId).toList());
        Set<Long> union = new TreeSet<>();
        boolean anyBound = false;
        for (Scope scope : byTerm.values()) {
            if (scope.isBroken()) {
                // 术语表里只要有一个归属损坏的术语，整表的写操作都必须拒绝：
                // 否则“改术语表”会成为绕过该术语 409 的旁路。
                return Scope.broken();
            }
            if (scope.status() == ScopeStatus.BOUND) {
                anyBound = true;
                union.addAll(scope.datasourceIds());
            }
        }
        return anyBound ? Scope.bound(union) : Scope.unbound();
    }

    @Override
    public Map<Long, Long> entityDatasourceIds(Collection<Long> entityIds) {
        Map<Long, Long> result = new HashMap<>();
        if (entityIds == null || entityIds.isEmpty()) {
            return result;
        }
        Set<Long> distinct = new LinkedHashSet<>(entityIds);
        distinct.remove(null);
        if (distinct.isEmpty()) {
            return result;
        }
        // 用 MyBatis-Plus 的批量读取：一次查询，不按实体逐个查（避免 N+1）。
        // 归属从 `entity_metadata.datasource_id` 在 Java 侧解析——不用 `JSON_EXTRACT`，
        // 那在测试库（H2 MySQL 模式）与真实 MySQL 上的返回形态不一致。
        List<MetadataEntity> entities = entityMapper.selectBatchIds(distinct);
        if (entities == null) {
            return result;
        }
        for (MetadataEntity entity : entities) {
            Long datasourceId = parseDatasourceId(entity.getEntityMetadata());
            if (datasourceId != null) {
                result.put(entity.getId(), datasourceId);
            }
        }
        return result;
    }

    @Override
    public void requireWritableScope(Long userId, String functionCode, Scope scope) {
        if (scope == null || scope.isBroken()) {
            throw new BusinessException(409, "术语的关联字段归属不完整，无法判定负责范围，已拒绝操作");
        }
        if (scope.status() == ScopeStatus.UNBOUND) {
            // 未关联任何数据源：只校验功能（切面已完成），不推导任何数据源权限。
            return;
        }
        // 排序后逐个校验：任一无权立即抛出 403，整体拒绝，调用方不得先写后校验。
        for (Long datasourceId : new TreeSet<>(scope.datasourceIds())) {
            adminGuard.requireDatasourceFunction(userId, functionCode, datasourceId);
        }
    }

    /** 从实体元数据解析数据源归属；解析不出按“没有归属”返回，由调用方 fail-closed。 */
    private Long parseDatasourceId(String entityMetadata) {
        if (entityMetadata == null || entityMetadata.isBlank()) {
            return null;
        }
        try {
            var node = OBJECT_MAPPER.readTree(entityMetadata);
            if (node.hasNonNull("datasource_id")) {
                return node.get("datasource_id").asLong();
            }
        } catch (Exception exception) {
            log.warn("解析术语关联实体的数据源归属失败: {}", exception.getMessage());
        }
        return null;
    }
}
