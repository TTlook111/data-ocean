package com.dataocean.module.prompt.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Prompt 模板视图对象。
 * <p>
 * 用于列表和详情接口的返回数据。
 * </p>
 */
@Data
public class PromptTemplateVO {

    /** 模板 ID */
    private Long id;

    /** 模板唯一编码 */
    private String templateCode;

    /** 模板名称 */
    private String templateName;

    /** 使用场景 */
    private String scenario;

    /** 当前编辑/工作流版本内容，线上内容独立见 activeContent。 */
    private String content;

    /** 当前版本号 */
    private Integer currentVersion;

    /** 生效版本与当前编辑版本独立；enabled=false 时运行端使用内置策略。 */
    private Integer activeVersion;
    private String activeContent;
    private String integrationStatus;

    /** 模板状态（DRAFT/PENDING_REVIEW/APPROVED/REJECTED） */
    private String status;

    /** 是否启用 */
    private Boolean enabled;

    /** 最后更新时间 */
    private LocalDateTime updatedAt;
}
