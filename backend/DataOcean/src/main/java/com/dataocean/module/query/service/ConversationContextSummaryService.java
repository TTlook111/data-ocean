package com.dataocean.module.query.service;

import com.dataocean.module.query.entity.dto.ConversationContextDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1QueryCandidateCatalogVO;

/** 会话长期上下文摘要服务。 */
public interface ConversationContextSummaryService {

    /**
     * 构建一次查询所需的上下文：长期摘要 + 最近对话。
     */
    ConversationContextDTO buildQueryContext(Long conversationId, Long userId, Long currentUserMessageId,
                                             IamS1QueryCandidateCatalogVO currentCatalog);

    /**
     * 异步刷新会话长期摘要。失败不能阻塞查询主链路。
     */
    void refreshAsync(Long conversationId, Long userId, Long datasourceId);
}
