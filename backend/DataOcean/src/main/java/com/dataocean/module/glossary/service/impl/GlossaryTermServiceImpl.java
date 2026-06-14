package com.dataocean.module.glossary.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.glossary.entity.Glossary;
import com.dataocean.module.glossary.entity.GlossaryTerm;
import com.dataocean.module.glossary.mapper.GlossaryMapper;
import com.dataocean.module.glossary.mapper.GlossaryTermMapper;
import com.dataocean.module.glossary.service.GlossaryTermService;
import com.dataocean.module.metadata.entity.MetadataEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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

    private final GlossaryMapper glossaryMapper;

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
}
