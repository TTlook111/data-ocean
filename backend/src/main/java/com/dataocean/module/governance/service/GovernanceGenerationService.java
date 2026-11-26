package com.dataocean.module.governance.service;

import com.dataocean.module.governance.entity.dto.GenerateScopeDTO;
import com.dataocean.module.governance.entity.vo.GenerationAcceptedVO;
import com.dataocean.module.governance.entity.vo.GenerationDetailVO;
import com.dataocean.module.governance.entity.vo.GenerationPreviewVO;

import java.util.Set;

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

    /**
     * 读取实时订阅有权得知的最小状态，建议正文继续通过任务详情核权。
     */
    StreamState streamState(long source, String requestId, long actor, Set<String> rights);

    /**
     * 持久任务状态及可供重新订阅的游标。
     *
     * @param state 最新任务状态
     * @param version 当前批内状态版本之和
     */
    record StreamState(String state, int version) {}
}
