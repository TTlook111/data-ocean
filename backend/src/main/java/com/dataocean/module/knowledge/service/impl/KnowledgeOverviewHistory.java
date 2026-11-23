package com.dataocean.module.knowledge.service.impl;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.overview.OverviewPeriod.Window;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentDependencyMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeOverviewStateMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeOverviewStateMapper.Basis;
import com.dataocean.module.knowledge.mapper.KnowledgeOverviewStateMapper.State;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 期末文档的发布链与维护状态来自事实台账，内容可用性沿用权威门禁。
 */
@Component
@RequiredArgsConstructor
class KnowledgeOverviewHistory {

    /**
     * 时点正式范围及其独立限制。
     */
    private final KnowledgeOverviewStateMapper mapper;

    /**
     * 取得固定文档修订关联的完整正式绑定。
     */
    private final KnowledgePublicationMapper bindings;

    /**
     * 正式引用需要核对实际固定的目标绑定。
     */
    private final KnowledgeDocumentDependencyMapper dependencies;

    /**
     * 当前源的有效治理和结构证据。
     */
    private final GovernanceCatalog catalog;

    /**
     * 共用正式问数可用资格与历史有证据时的业务事实检查。
     */
    private final KnowledgeVersionUse versions;

    /**
     * 读取各期末的文档，计数和分页使用同一份判断。
     */
    Map<Integer, List<DocumentState>> evaluate(Long source, List<Window> windows, LocalDateTime now) {
        var last = windows.get(windows.size() - 1);
        var groups = mapper.states(source, last.end(), windows).stream().collect(Collectors.groupingBy(State::ordinal));
        var basis = mapper.basis(source).stream().collect(Collectors.toMap(Basis::sourceId, Basis::changedAt));
        var result = new HashMap<Integer, List<DocumentState>>();
        var currentSources = new HashMap<Long, Source>();
        var checkedFacts = new HashMap<Long, Boolean>();
        var pinned = new HashMap<Long, List<Long>>();
        var current = new HashMap<Long, Boolean>();

        for (var window : windows) {
            // 每个期末独立重建依赖链，当前证据检查在本次读取中共享缓存。
            var rows = groups.getOrDefault(window.index(), List.of());
            var active = rows.stream().collect(Collectors.toMap(State::bindingId, Function.identity()));
            var evaluated = rows.stream()
                    .map(state -> {
                        // 当期最后时点按正式问数门禁核实，历史使用可证明的原时点事实。
                        Boolean available = window.end().equals(now)
                                ? current.computeIfAbsent(state.bindingId(), ignored -> live(state))
                                : historical(state, window.end(), active, basis, currentSources, checkedFacts, pinned);
                        return new DocumentState(state, available);
                    })
                    .toList();
            result.put(window.index(), evaluated);
        }
        return result;
    }

    /**
     * 当前平台知识可用性直接复用普通问数的源、版本和事实门禁。
     */
    private boolean live(State state) {
        if (!state.verified()) {
            // 构建状态和核实时间仍须共同证明已就绪。
            return false;
        }
        try {
            versions.requireQueryableBinding(state.sourceId(), state.bindingId());
            return true;
        } catch (BizIllegalException unavailable) {
            // 明确的业务状态拒绝代表当前不可使用。
            return false;
        }
    }

    /**
     * 检查原实际依赖链。明确的阻断优先于证据不足的时点。
     */
    private Boolean historical(
            State state,
            LocalDateTime until,
            Map<Long, State> active,
            Map<Long, LocalDateTime> basis,
            Map<Long, Source> currentSources,
            Map<Long, Boolean> checkedFacts,
            Map<Long, List<Long>> pinned) {
        var pending = new ArrayDeque<>(List.of(state.bindingId()));
        var visited = new HashSet<Long>();
        boolean unknown = false;
        while (!pending.isEmpty()) {
            // 逐份核对固定的原依赖，重叠分支只验证一次。
            long id = pending.removeFirst();
            if (!visited.add(id)) {
                continue;
            }
            var target = active.get(id);
            if (target == null
                    || target.sourceId() != state.sourceId()
                    || !target.verified()
                    || target.paused()
                    || target.forbidden()
                    || Boolean.FALSE.equals(target.modelEnabled())) {
                // 依赖断裂、明确的停用及限制均足以证明不可用。
                return false;
            }
            var changedAt = basis.get(target.sourceId());
            if (changedAt == null || !changedAt.isBefore(until)) {
                // 后续治理或结构变化使当时正文的可用性证据不足。
                unknown = true;
            } else if (!checkedFacts.computeIfAbsent(id, ignored -> historicalBasis(target, currentSources))) {
                return false;
            }
            if (target.modelEnabled() == null) {
                // 旧的模型状态变动时间无法补造。
                unknown = true;
            }
            pending.addAll(pinned.computeIfAbsent(id, dependencies::requiredBindings));
        }
        return unknown ? null : Boolean.TRUE;
    }

    /**
     * 在源和治理事实相同的前提下，用原绑定内容复核业务定义。
     */
    private boolean historicalBasis(State state, Map<Long, Source> currentSources) {
        var binding = bindings.get(state.sourceId(), state.bindingId());
        if (binding == null) {
            return false;
        }
        try {
            var source = currentSources.computeIfAbsent(state.sourceId(), id -> catalog.source(id, false));
            versions.requireSourceBasis(source);
            versions.requireGovernanceBasis(source, binding);
            return true;
        } catch (BizIllegalException unavailable) {
            // 具体治理拒绝仍使用原领域规则。
            return false;
        }
    }

    /**
     * 时点文档的已知状态。
     *
     * @param state 确切绑定和维护状态
     * @param available 正式知识可使用性，历史证据不足时为空
     */
    record DocumentState(State state, Boolean available) {}
}
