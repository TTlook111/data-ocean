package com.dataocean.module.knowledge.service;

import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeReleasePrepareDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeReleaseCandidateVO;

/**
 * 供发布页面按同源文档准备候选并回读实际记录。
 */
public interface KnowledgeReleaseService {

    /**
     * 使用同一公共命令固定候选依赖。
     */
    MutationVO prepare(long source, String command, KnowledgeReleasePrepareDTO input);

    /**
     * 以稳定排序返回同源文档已有的候选。
     */
    PageVO<KnowledgeReleaseCandidateVO> list(long source, Long document, String keyword, String status, PageQuery page);
}
