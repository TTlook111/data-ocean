package com.dataocean.module.audit.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 仅保存失败动作的必要关联，不接收请求正文、密码或异常原文。
 */
@Data
@AllArgsConstructor
public class AuditFailureDTO {

    /**
     * 根据实际路由和方法确定的操作代码。
     */
    private String actionCode;

    /**
     * 被操作对象的业务类型。
     */
    private String targetType;

    /**
     * 路径中的合法资源标识，无法确定时为空。
     */
    private String targetId;

    /**
     * 最终 HTTP 失败状态，用于区分业务拒绝与系统失败。
     */
    private int httpStatus;
}
