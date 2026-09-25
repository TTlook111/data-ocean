package com.dataocean.module.query.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.query.entity.dto.IamS1QueryAskRequestDTO;
import com.dataocean.module.query.entity.vo.QueryTaskVO;
import com.dataocean.module.query.entity.vo.ConversationMessageVO;
import com.dataocean.module.query.entity.query.QueryHistoryQuery;

import java.util.List;

/** B3 IAM-SIMPLE-1 查询任务与当前权限复查服务。 */
public interface IamS1QueryService {
    String submit(Long userId, IamS1QueryAskRequestDTO request);
    Long conversationId(String taskId, Long userId);
    QueryTaskVO get(String taskId, Long userId);
    Page<QueryTaskVO> history(Long userId, QueryHistoryQuery query);
    void cancel(String taskId, Long userId);
    void complete(String taskId, String resultJson);
    void feedback(String taskId, Long userId, String feedbackType);
    List<java.util.Map<String, Object>> export(String taskId, Long userId);
    List<?> conversations(Long userId, Long datasourceId);
    List<ConversationMessageVO> conversationMessages(Long conversationId, Long userId, Integer page, Integer pageSize);
    void archiveConversation(Long conversationId, Long userId);
}
