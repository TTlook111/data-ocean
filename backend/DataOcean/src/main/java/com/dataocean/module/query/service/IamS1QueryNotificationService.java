package com.dataocean.module.query.service;

import com.dataocean.module.query.entity.vo.QueryTaskVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 页面通知边界：只管理订阅与交付，任务状态仍由查询服务持久化。 */
public interface IamS1QueryNotificationService {
    /** 调用方完成任务归属和当前权限检查后，建立一个独立的页面订阅。 */
    SseEmitter subscribe(String taskId, Long permissionRevision);

    /** 仅接收经过查询读取路径重新鉴权、脱敏和裁剪的终态结果。 */
    void sendResult(String taskId, QueryTaskVO result);

    /** 仅接收当前用户仍有权读取的任务进度，不参与任务状态转换。 */
    void sendProgress(String taskId, QueryTaskVO progress);

    /** 无法安全交付结果时只通知可公开的原因，不附带原始结果。 */
    void sendError(String taskId, String message);
}
