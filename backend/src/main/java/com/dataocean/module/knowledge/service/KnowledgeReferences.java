package com.dataocean.module.knowledge.service;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.governance.entity.vo.GovernanceTargetVO;
import com.dataocean.module.governance.service.GovernanceKnowledgeService;
import com.dataocean.module.governance.service.GovernanceTarget;
import com.dataocean.module.knowledge.entity.dto.KnowledgeDocumentDraftDTO;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper.Reference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 固定章节引用，按当前确认展开同源必要依赖。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeReferences {

    /**
     * 治理模块核对修订、结构及当前禁止。
     */
    private final GovernanceKnowledgeService governance;

    /**
     * 保存草稿时展开实际依赖并拒绝冲突。
     */
    public List<Reference> capture(Source source, KnowledgeDocumentDraftDTO body) {
        var output = new ArrayList<Reference>();
        for (int index = 0; index < body.getSections().size(); index++) {
            // 同一事实可用于不同章节，每个章节均保留自身的依据。
            var resolved = new LinkedHashMap<GovernanceTarget, Reference>();
            for (var input : body.getSections().get(index).getFacts()) {
                var target = new GovernanceTarget(
                        source.id(), Ids.parse(input.getObjectId()), input.getKind(), Ids.parse(input.getTargetId()));
                capture(source, index, target, Ids.parse(input.getRevisionId()), resolved, new HashSet<>());
            }
            output.addAll(resolved.values());
        }
        return List.copyOf(output);
    }

    /**
     * 确认或发布时重核实际固定依据，普通快照推进不会替换业务修订。
     */
    public void verify(Source source, List<Reference> references) {
        verify(source, references, false);
    }

    /**
     * 在保留现行禁止的前提下核对目标版本的确切结构和确认依据。
     */
    public void verifyRollbackBasis(Source source, List<Reference> references) {
        verify(source, references, true);
    }

    /**
     * 普通使用与回退核对共用确切引用比对，区别只在回退还原不改变使用限制。
     */
    private void verify(Source source, List<Reference> references, boolean rollback) {
        for (Reference stored : references) {
            // 检查实际世代、治理修订和结构修订，旧内容继续保留以供追溯。
            var target = new GovernanceTarget(source.id(), stored.objectId(), stored.kind(), stored.targetId());
            var current =
                    rollback ? governance.requireRollbackBasis(source, target) : governance.require(source, target);
            if (stored.sourceId() != source.id()
                    || stored.generation() != current.getGeneration()
                    || stored.factRevisionId() != Ids.parse(current.getRevisionId())
                    || stored.structureRevisionId() != Ids.parse(current.getStructureRevisionId())) {
                throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "治理依据已变化，请重新核对：" + stored.label());
            }
        }
    }

    /**
     * 依据图以稳定身份去重，保留主动选择并检测循环。
     */
    private void capture(
            Source source,
            int section,
            GovernanceTarget target,
            Long expected,
            Map<GovernanceTarget, Reference> resolved,
            Set<GovernanceTarget> path) {
        if (path.contains(target)) {
            throw BizIllegalException.state("所选治理依据包含循环依赖");
        }
        var existing = resolved.get(target);
        if (existing != null && expected == null) {
            return;
        }
        path.add(target);
        var current = governance.require(source, target);
        long revision = Ids.parse(current.getRevisionId());
        if (expected != null && expected != revision) {
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "所选治理确认已变化，请重新选择");
        }
        resolved.put(
                target,
                new Reference(
                        section,
                        source.id(),
                        target.object(),
                        current.getGeneration(),
                        target.kind(),
                        target.target(),
                        revision,
                        Ids.parse(current.getStructureRevisionId()),
                        expected != null,
                        current.getLabel()));
        if (existing == null) {
            for (GovernanceTargetVO ref : current.getDependencies()) {
                // 可见性与源约束继续由治理服务处理，各分支独立清理访问路径。
                var dependency = new GovernanceTarget(
                        source.id(), Ids.parse(ref.getObjectId()), ref.getKind(), Ids.parse(ref.getTargetId()));
                capture(source, section, dependency, null, resolved, path);
            }
        }
        path.remove(target);
    }
}
