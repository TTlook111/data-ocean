package com.dataocean.module.knowledge.service;

import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeReleasePrepareDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeReleaseTaskVO;

/**
 * 供发布页面按同源文档创建准备任务并回读记录。
 */
public interface KnowledgeReleaseService {

    /**
     * 根据已确认文档保存准备任务。
     */
    MutationVO prepare(long source, String command, KnowledgeReleasePrepareDTO input);

    /**
     * 以稳定排序返回同源文档已有的任务。
     */
    PageVO<KnowledgeReleaseTaskVO> list(long source, Long document, String keyword, String status, PageQuery page);
}
