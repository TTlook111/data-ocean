package com.dataocean.module.prompt.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Prompt 版本视图对象。
 * <p>
 * 用于版本历史列表的返回数据。
 * </p>
 */
@Data
public class PromptVersionVO {

    /** 版本记录 ID */
    private Long id;

    /** 版本号 */
    private Integer versionNo;

    /** 该版本的模板内容 */
    private String content;

    /** 变更摘要 */
    private String changeSummary;

    /** 是否为当前活跃版本 */
    private Boolean isActive;

    /** 创建人 ID */
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
