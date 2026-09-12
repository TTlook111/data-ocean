package com.dataocean.module.glossary.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dataocean.module.glossary.entity.Glossary;
import com.dataocean.module.glossary.mapper.GlossaryMapper;
import com.dataocean.module.glossary.service.GlossaryService;
import com.dataocean.module.glossary.service.GlossaryTermService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 术语表服务实现
 *
 * @author dataocean
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlossaryServiceImpl extends ServiceImpl<GlossaryMapper, Glossary>
        implements GlossaryService {

    private final GlossaryTermService glossaryTermService;

    @Transactional
    @Override
    public int deleteGlossary(Long glossaryId) {
        // 先级联删除其下术语并清理关联关系，再删术语表本身。两步放在同一事务里，
        // 避免出现「术语表已删、术语还在」的中间态。
        int removedTerms = glossaryTermService.deleteTermsOfGlossary(glossaryId);
        removeById(glossaryId);
        log.info("术语表已删除 glossaryId={} 同时清理术语 {} 条", glossaryId, removedTerms);
        return removedTerms;
    }
}
