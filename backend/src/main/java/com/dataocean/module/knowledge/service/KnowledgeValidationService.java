package com.dataocean.module.knowledge.service;

import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeValidationDTO.Judgment;
import com.dataocean.module.knowledge.entity.dto.KnowledgeValidationDTO.Start;
import com.dataocean.module.knowledge.entity.vo.KnowledgeValidationVO;
import com.dataocean.module.knowledge.service.KnowledgeValidationWire.Input;

/**
 * 发布候选的验证办理、读回和工作者许可。
 */
public interface KnowledgeValidationService {

    /**
     * 重新执行固定候选的全部审核题，保留之前运行。
     */
    MutationVO start(long sourceId, long id, String command, Start input);

    /**
     * 向现有受认证工作者一次性交付确切代次及获准题干。
     */
    Input claimInput(String id, int version);

    /**
     * 实际调用、召回及交付前检查任务和原题执行资格。
     */
    void recheck(String id, int version);

    /**
     * 当前说明题的裁定必须绑定具体运行、题目和这次页面版本。
     */
    MutationVO judge(long sourceId, long taskId, String runId, long questionId, String command, Judgment input);

    /**
     * 提供当前和历史运行，实际数据仅授权可见。
     */
    KnowledgeValidationVO detail(long source, long taskId, String requestedRun);
}
