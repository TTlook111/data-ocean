package com.dataocean.module.query.entity.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Agent 执行请求 DTO
 * <p>
 * 用于 Java 调用 Python Agent 服务的请求体。
 * </p>
 *
 * @author dataocean
 */
@Data
@Builder
public class AgentExecuteRequest {

    /** 任务 ID */
    private String taskId;

    /** 数据源 ID */
    private Long datasourceId;

    /** 用户 ID */
    private Long userId;

    /** 用户问题 */
    private String question;

    /** 活跃快照 ID */
    private Long activeSnapshotId;

    /** 连接配置 */
    private Map<String, Object> connectionConfig;

    /** 用户权限 */
    private Map<String, Object> userPermissions;

    /** 对话历史 */
    private List<Map<String, String>> conversationHistory;

    /** 降级 chunks */
    private List<Map<String, Object>> fallbackChunks;

    /** 术语表 */
    private List<Map<String, String>> glossaryTerms;
}
