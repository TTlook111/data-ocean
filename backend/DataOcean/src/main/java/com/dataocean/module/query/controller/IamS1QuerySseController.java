package com.dataocean.module.query.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.query.entity.po.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.permission.s1.mapper.IamS1PermissionRevisionMapper;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.query.service.IamS1QueryNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** S1 SSE：建立连接前先做任务所有者和协议检查。 */
@RestController
@RequestMapping("/api/iam-s1/query")
@RequiredArgsConstructor
public class IamS1QuerySseController {
    private final QueryTaskMapper queryTaskMapper;
    private final IamS1PermissionRevisionMapper permissionRevisionMapper;
    private final IamS1AuthorizationResolver authorizationResolver;
    // Controller 只决定能否订阅，通知组件独立管理连接与发送生命周期。
    private final IamS1QueryNotificationService notificationService;

    @GetMapping(value = "/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String taskId) {
        Long userId = UserContext.currentUserId();
        QueryTask task = queryTaskMapper.selectOne(new LambdaQueryWrapper<QueryTask>()
                .eq(QueryTask::getTaskId, taskId).eq(QueryTask::getUserId, userId));
        Long currentRevision = permissionRevisionMapper.selectCurrentRevision();
        if (task == null || !"IAM-SIMPLE-1".equals(task.getIamProtocolVersion())
                || !authorizationResolver.hasGlobalFunction(userId, "query:use")
                || currentRevision == null || !currentRevision.equals(task.getPermissionRevision())) {
            throw new BusinessException("S1 任务不存在或无权访问");
        }
        // 保留任务所有者、协议、功能与权限修订校验，通过后才注册页面连接。
        return notificationService.subscribe(taskId, task.getPermissionRevision());
    }
}
