package com.dataocean.module.glossary.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.glossary.entity.Glossary;
import com.dataocean.module.glossary.entity.GlossaryTerm;
import com.dataocean.module.glossary.mapper.GlossaryMapper;
import com.dataocean.module.glossary.mapper.GlossaryTermMapper;
import com.dataocean.module.glossary.service.GlossaryTermService;
import com.dataocean.module.metadata.entity.MetadataEntity;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 术语条目服务实现
 *
 * @author dataocean
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlossaryTermServiceImpl extends ServiceImpl<GlossaryTermMapper, GlossaryTerm>
        implements GlossaryTermService {

    /** 术语作为关系源端时的 source_type 取值 */
    private static final String SOURCE_TYPE_GLOSSARY_TERM = "GLOSSARY_TERM";

    private final GlossaryMapper glossaryMapper;
    private final MetadataRelationshipService relationshipService;

    @Override
    public List<GlossaryTerm> getApprovedTerms(Long glossaryId) {
        return baseMapper.selectApprovedByGlossaryId(glossaryId);
    }

    @Transactional
    @Override
    public GlossaryTerm createTerm(GlossaryTerm term) {
        // 校验术语表存在
        Glossary glossary = glossaryMapper.selectById(term.getGlossaryId());
        if (glossary == null) {
            throw new BusinessException("术语表不存在");
        }

        // 自动生成 FQN
        if (term.getFqn() == null || term.getFqn().isBlank()) {
            term.setFqn(MetadataEntity.fqnGlossaryTerm(glossary.getName(), term.getName()));
        }

        // 检查 FQN 唯一
        GlossaryTerm existing = baseMapper.selectByFqn(term.getFqn());
        if (existing != null) {
            throw new BusinessException("术语 FQN 已存在: " + term.getFqn());
        }

        // 设置初始状态
        if (term.getStatus() == null) {
            term.setStatus(GlossaryTerm.STATUS_DRAFT);
        }

        baseMapper.insert(term);
        log.info("术语已创建 id={} fqn={}", term.getId(), term.getFqn());
        return term;
    }

    @Transactional
    @Override
    public void submitForReview(Long termId) {
        GlossaryTerm term = baseMapper.selectById(termId);
        if (term == null) {
            throw new BusinessException("术语不存在");
        }
        if (!GlossaryTerm.STATUS_DRAFT.equals(term.getStatus())
                && !GlossaryTerm.STATUS_REJECTED.equals(term.getStatus())) {
            throw new BusinessException("只有 DRAFT 或 REJECTED 状态的术语才能提交审核");
        }
        term.setStatus(GlossaryTerm.STATUS_PENDING_REVIEW);
        baseMapper.updateById(term);
        log.info("术语已提交审核 termId={}", termId);
    }

    @Transactional
    @Override
    public void reviewTerm(Long termId, Long reviewerId, boolean approved, String reason) {
        GlossaryTerm term = baseMapper.selectById(termId);
        if (term == null) {
            throw new BusinessException("术语不存在");
        }
        if (!GlossaryTerm.STATUS_PENDING_REVIEW.equals(term.getStatus())) {
            throw new BusinessException("只有 PENDING_REVIEW 状态的术语才能审核");
        }

        term.setReviewerId(reviewerId);
        term.setReviewedAt(LocalDateTime.now());
        if (approved) {
            term.setStatus(GlossaryTerm.STATUS_APPROVED);
            log.info("术语审核通过 termId={} reviewerId={}", termId, reviewerId);
        } else {
            term.setStatus(GlossaryTerm.STATUS_REJECTED);
            log.info("术语审核拒绝 termId={} reviewerId={} reason={}", termId, reviewerId, reason);
        }
        baseMapper.updateById(term);
    }

    @Transactional
    @Override
    public GlossaryTerm updateTerm(Long termId, GlossaryTerm patch) {
        GlossaryTerm existing = baseMapper.selectById(termId);
        if (existing == null) {
            throw new BusinessException("术语不存在");
        }
        // 与 submitForReview 保持一致：只有草稿或被拒的术语可以修改。
        // 状态机原先从 APPROVED 没有出边，但 updateTerm 直接 updateById(请求体) 且无校验，
        // 形成「合法路径被限制、绕过路径不受限」的倒挂。现在已通过的术语必须先退回草稿。
        if (!GlossaryTerm.STATUS_DRAFT.equals(existing.getStatus())
                && !GlossaryTerm.STATUS_REJECTED.equals(existing.getStatus())) {
            throw new BusinessException(
                    "只有 DRAFT 或 REJECTED 状态的术语才能修改，当前状态：" + existing.getStatus()
                            + "；已通过的术语请先退回草稿");
        }

        // 白名单赋值：只接受内容字段。此前直接把请求体交给 updateById，请求体可以携带
        // status / reviewerId / reviewedAt 等字段，等于绕过状态机直接改写审核结果。
        existing.setDisplayName(patch.getDisplayName());
        existing.setDescription(patch.getDescription());
        existing.setSynonyms(patch.getSynonyms());
        existing.setRelatedTerms(patch.getRelatedTerms());
        existing.setParentId(patch.getParentId());

        // 改名必须同步重建 FQN：否则 fqn 与 name 脱节，且旧 FQN 会一直占用 uk_term_fqn
        if (StringUtils.hasText(patch.getName()) && !patch.getName().equals(existing.getName())) {
            Glossary glossary = glossaryMapper.selectById(existing.getGlossaryId());
            if (glossary == null) {
                throw new BusinessException("术语表不存在");
            }
            String newFqn = MetadataEntity.fqnGlossaryTerm(glossary.getName(), patch.getName());
            GlossaryTerm sameFqn = baseMapper.selectByFqn(newFqn);
            if (sameFqn != null && !sameFqn.getId().equals(termId)) {
                throw new BusinessException("术语 FQN 已存在: " + newFqn);
            }
            existing.setName(patch.getName());
            existing.setFqn(newFqn);
        }

        baseMapper.updateById(existing);
        log.info("术语已更新 termId={}", termId);
        return existing;
    }

    @Transactional
    @Override
    public void revertToDraft(Long termId) {
        GlossaryTerm term = baseMapper.selectById(termId);
        if (term == null) {
            throw new BusinessException("术语不存在");
        }
        if (!GlossaryTerm.STATUS_APPROVED.equals(term.getStatus())) {
            throw new BusinessException("只有已通过的术语才能退回草稿，当前状态：" + term.getStatus());
        }
        // 用 UpdateWrapper 显式置 null：updateById 会跳过 null 字段，清不掉审核记录。
        // 退回后原审核结论不再代表当前内容，留着会形成「已审核」的假标记且无法追溯。
        baseMapper.update(null, new LambdaUpdateWrapper<GlossaryTerm>()
                .eq(GlossaryTerm::getId, termId)
                .set(GlossaryTerm::getStatus, GlossaryTerm.STATUS_DRAFT)
                .set(GlossaryTerm::getReviewerId, null)
                .set(GlossaryTerm::getReviewedAt, null));
        log.info("术语已退回草稿 termId={}", termId);
    }

    @Transactional
    @Override
    public void deleteTerm(Long termId) {
        GlossaryTerm term = baseMapper.selectById(termId);
        if (term == null) {
            return;
        }
        Set<Long> removedIds = Set.of(termId);
        clearParentReferencesTo(removedIds);
        int removedRelations = removeGlossaryOfRelationsOf(removedIds);
        baseMapper.deleteById(termId);
        log.info("术语已删除 termId={} 清理 GLOSSARY_OF 关系 {} 条", termId, removedRelations);
    }

    @Transactional
    @Override
    public int deleteTermsOfGlossary(Long glossaryId) {
        List<GlossaryTerm> terms = baseMapper.selectList(
                new LambdaQueryWrapper<GlossaryTerm>().eq(GlossaryTerm::getGlossaryId, glossaryId));
        if (terms.isEmpty()) {
            return 0;
        }
        Set<Long> removedIds = new LinkedHashSet<>();
        for (GlossaryTerm term : terms) {
            removedIds.add(term.getId());
        }
        clearParentReferencesTo(removedIds);
        int removedRelations = removeGlossaryOfRelationsOf(removedIds);
        baseMapper.delete(new LambdaQueryWrapper<GlossaryTerm>().eq(GlossaryTerm::getGlossaryId, glossaryId));
        log.info("术语表下术语已级联删除 glossaryId={} 术语 {} 条 清理 GLOSSARY_OF 关系 {} 条",
                glossaryId, removedIds.size(), removedRelations);
        return removedIds.size();
    }

    /**
     * 清理指向这些术语的父术语引用。
     * <p>
     * `glossary_term.parent_id` 没有外键约束，删除术语后指向它的子术语会留下悬空父节点。
     * </p>
     *
     * @param removedTermIds 即将被删除的术语 ID 集合
     */
    private void clearParentReferencesTo(Set<Long> removedTermIds) {
        if (removedTermIds.isEmpty()) {
            return;
        }
        baseMapper.update(null, new LambdaUpdateWrapper<GlossaryTerm>()
                .in(GlossaryTerm::getParentId, removedTermIds)
                .set(GlossaryTerm::getParentId, null));
    }

    /**
     * 清理这些术语作为源端的 `GLOSSARY_OF` 关系。
     * <p>
     * `metadata_relationship` 没有外键约束，删除术语后关系会变成孤儿，指向已删除的术语，
     * 使实体图谱与血缘查询返回悬空节点。
     * </p>
     *
     * @param termIds 术语 ID 集合
     * @return 清理的关系数量
     */
    private int removeGlossaryOfRelationsOf(Set<Long> termIds) {
        int removed = 0;
        for (Long termId : termIds) {
            for (MetadataRelationship rel : relationshipService.getBySource(termId, SOURCE_TYPE_GLOSSARY_TERM)) {
                if (MetadataRelationship.TYPE_GLOSSARY_OF.equals(rel.getRelationType())) {
                    relationshipService.removeById(rel.getId());
                    removed++;
                }
            }
        }
        return removed;
    }
}
