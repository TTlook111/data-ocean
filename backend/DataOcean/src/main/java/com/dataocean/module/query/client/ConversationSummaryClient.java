package com.dataocean.module.query.client;

import java.util.List;
import java.util.Map;

/** Python 会话摘要接口客户端。 */
public interface ConversationSummaryClient {

    /**
     * 调用 Python 摘要 LLM，合并已有摘要和新增消息。
     */
    Map<String, Object> summarize(List<Map<String, Object>> messages,
                                  Map<String, Object> previousSummary);
}
