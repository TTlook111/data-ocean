package com.dataocean.module.query.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.permission.s1.mapper.IamS1PermissionRevisionMapper;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** S1 SSE：建立连接前先做任务所有者和协议检查。 */
@RestController
@RequestMapping("/api/iam-s1/query")
@RequiredArgsConstructor
@Slf4j
public class IamS1QuerySseController {
    private final QueryTaskMapper queryTaskMapper;
    private final IamS1PermissionRevisionMapper permissionRevisionMapper;
    private final IamS1AuthorizationResolver authorizationResolver;
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

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
        SseEmitter emitter = new SseEmitter(120_000L);
        emitters.put(taskId, emitter);
        emitter.onCompletion(() -> emitters.remove(taskId));
        emitter.onTimeout(() -> emitters.remove(taskId));
        emitter.onError(error -> emitters.remove(taskId));
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of(
                    "taskId", taskId, "protocolVersion", "IAM-SIMPLE-1",
                    "permissionRevision", task.getPermissionRevision())));
        } catch (IOException ex) {
            emitters.remove(taskId);
        }
        return emitter;
    }

    public void sendResult(String taskId, Object result) {
        SseEmitter emitter = emitters.get(taskId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name("result").data(result));
            emitter.complete();
        } catch (IOException ex) {
            log.debug("S1 SSE 结果推送失败 taskId={}", taskId);
        } finally {
            emitters.remove(taskId);
        }
    }
}
