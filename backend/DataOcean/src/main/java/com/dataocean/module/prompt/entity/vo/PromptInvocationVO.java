package com.dataocean.module.prompt.entity.vo;
import lombok.Data;
import java.time.LocalDateTime;
/** 模型返回不等于格式校验、审核或业务验收通过。 */
@Data
public class PromptInvocationVO {
    private String invocationId;
    private String taskId;
    private String sceneCode;
    private Integer versionNo;
    private String source;
    private String contractVersion;
    private String contentHash;
    private String fallbackReason;
    private String outcome;
    private Long durationMs;
    private LocalDateTime createdAt;
}
