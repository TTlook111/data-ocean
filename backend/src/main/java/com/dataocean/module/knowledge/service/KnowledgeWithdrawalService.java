package com.dataocean.module.knowledge.service;

import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeWithdrawalDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeWithdrawalVO.Detail;
import com.dataocean.module.knowledge.entity.vo.KnowledgeWithdrawalVO.Preview;

/**
 * 正式知识撤下的影响核对、办理与历史。
 */
public interface KnowledgeWithdrawalService {

    /**
     * 只读查询实际清单，并签发与本次显示完全一致的核对凭据。
     */
    Preview preview(long source, long binding);

    /**
     * 来源锁内重新核对完整范围，再保存同一撤下批次。
     */
    MutationVO withdraw(long source, long binding, String command, KnowledgeWithdrawalDTO input);

    /**
     * 已撤下仍可读取历史批次和关闭时实际采用的范围。
     */
    Detail history(long source, long binding);
}
