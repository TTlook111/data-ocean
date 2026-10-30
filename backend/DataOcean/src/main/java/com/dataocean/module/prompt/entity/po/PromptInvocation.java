package com.dataocean.module.prompt.entity.po;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
/** 仅保存策略来源与调用结果元信息，不保存业务输入、提示词或密钥。 */
@Data
@TableName("prompt_invocation")
public class PromptInvocation {
    @TableId(type = IdType.AUTO)
    private Long id;
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
