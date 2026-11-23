package com.dataocean.module.knowledge.service;

import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeVersionRestoreDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeVersionControlVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeVersionControlVO.RunDetail;

/**
 * 当前正式绑定的隔离恢复验证和限制解除。
 */
public interface KnowledgeRecoveryService {

    /**
     * 运行当前正式绑定的隔离验证，保留原发布和原暂停状态。
     */
    MutationVO startRecovery(long sourceId, long bindingId, long pauseId, String command);

    /**
     * 有权人员确认风险消除，再次核验当前版本、题单、证据和完整索引。
     */
    MutationVO restore(long sourceId, long bindingId, long pauseId, String command, KnowledgeVersionRestoreDTO input);

    /**
     * 版本详情保留暂停范围与历史，旧依据不会显示成可恢复。
     */
    KnowledgeVersionControlVO controls(long sourceId, long id);

    /**
     * 验证页可重读旧轮的技术证据，但只允许办理当前有效的一轮。
     */
    RunDetail recoveryDetail(long sourceId, long id, String runId);
}
