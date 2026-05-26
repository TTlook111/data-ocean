package com.dataocean.module.prompt.entity.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Prompt 模板视图对象
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

    /** 当前活跃版本内容 */
    private String content;

    /** 当前版本号 */
    private Integer currentVersion;

    /** 是否启用 */
    private Boolean enabled;

    /** 最后更新时间 */
    private LocalDateTime updatedAt;
}
