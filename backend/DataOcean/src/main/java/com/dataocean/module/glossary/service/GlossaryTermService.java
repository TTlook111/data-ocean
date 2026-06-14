package com.dataocean.module.glossary.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dataocean.module.glossary.entity.GlossaryTerm;

import java.util.List;

/**
 * 术语条目服务接口
 *
 * @author dataocean
 */
public interface GlossaryTermService extends IService<GlossaryTerm> {

    /**
     * 查询指定术语表下所有已审核通过的术语
     */
    List<GlossaryTerm> getApprovedTerms(Long glossaryId);

    /**
     * 创建术语（自动设置 FQN 和初始状态）
     */
    GlossaryTerm createTerm(GlossaryTerm term);

    /**
     * 提交术语审核
     */
    void submitForReview(Long termId);

    /**
     * 审核术语（通过或拒绝）
     */
    void reviewTerm(Long termId, Long reviewerId, boolean approved, String reason);
}
