package com.dataocean.module.governance.service;

import com.dataocean.module.governance.entity.dto.GenerateScopeDTO;
import com.dataocean.module.governance.entity.vo.GenerationAcceptedVO;
import com.dataocean.module.governance.entity.vo.GenerationDetailVO;
import com.dataocean.module.governance.entity.vo.GenerationPreviewVO;

/**
 * 浏览器生成任务的核对、接收和结果回读。
 */
public interface GovernanceGenerationService {

    /**
     * 查看本次生成范围和可披露的固定资料输入。
     */
    GenerationPreviewVO preview(long source, long object, GenerateScopeDTO input);

    /**
     * 原子接收独立生成任务。
     */
    GenerationAcceptedVO accept(long source, long object, GenerateScopeDTO input, String command);

    /**
     * 按当前资格回读安全任务和实际建议。
     */
    GenerationDetailVO detail(long source, String requestId);
}
