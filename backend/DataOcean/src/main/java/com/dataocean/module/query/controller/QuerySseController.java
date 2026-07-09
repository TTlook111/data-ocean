package com.dataocean.module.query.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.query.service.QueryTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 查询任务 SSE 推送控制器
 * <p>
 * 提供实时进度推送，替代前端轮询机制。
 * </p>
 *
 * @author dataocean
 */
@RestController
@RequestMapping("/api/query")
@RequiredArgsConstructor
@Slf4j
public class QuerySseController {

    private final QueryTaskService queryTaskService;

    /** 活跃的 SSE 连接：taskId → SseEmitter */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 建立 SSE 连接
     * <p>
     * 前端通过 EventSource 连接此端点，接收实时进度推送。
     * </p>
     *
     * @param taskId 任务 ID
     * @return SseEmitter 实例
     */
    @GetMapping(value = "/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String taskId) {
        Long userId = UserContext.currentUserId();
        log.debug("建立 SSE 连接 taskId={} userId={}", taskId, userId);

        // 创建 SseEmitter，超时时间 120 秒
        SseEmitter emitter = new SseEmitter(120_000L);

        // 注册连接
        emitters.put(taskId, emitter);

        // 注册回调：连接完成、超时、错误时清理
        emitter.onCompletion(() -> {
            emitters.remove(taskId);
            log.debug("SSE 连接完成 taskId={}", taskId);
        });

        emitter.onTimeout(() -> {
            emitters.remove(taskId);
            log.debug("SSE 连接超时 taskId={}", taskId);
        });

        emitter.onError(e -> {
            emitters.remove(taskId);
            log.debug("SSE 连接错误 taskId={} error={}", taskId, e.getMessage());
        });

        // 发送初始连接成功事件
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("taskId", taskId, "status", "connected")));
        } catch (IOException e) {
            log.warn("发送 SSE 连接事件失败 taskId={}", taskId);
        }

        return emitter;
    }

    /**
     * 推送进度事件（供内部调用）
     * <p>
     * Python Agent 消费 SSE 时，Java 同步推送给前端。
     * </p>
     *
     * @param taskId 任务 ID
     * @param data   事件数据
     */
    public void sendProgress(String taskId, Object data) {
        SseEmitter emitter = emitters.get(taskId);
        if (emitter == null) {
            return;
        }

        try {
            emitter.send(SseEmitter.event()
                    .name("progress")
                    .data(data));
        } catch (IOException e) {
            log.debug("推送 SSE 进度失败 taskId={}", taskId);
            emitters.remove(taskId);
        }
    }

    /**
     * 推送结果事件（供内部调用）
     *
     * @param taskId 任务 ID
     * @param data   结果数据
     */
    public void sendResult(String taskId, Object data) {
        SseEmitter emitter = emitters.get(taskId);
        if (emitter == null) {
            return;
        }

        try {
            emitter.send(SseEmitter.event()
                    .name("result")
                    .data(data));
            emitter.complete();
        } catch (IOException e) {
            log.debug("推送 SSE 结果失败 taskId={}", taskId);
        } finally {
            emitters.remove(taskId);
        }
    }

    /**
     * 推送错误事件（供内部调用）
     *
     * @param taskId 任务 ID
     * @param error  错误信息
     */
    public void sendError(String taskId, String error) {
        SseEmitter emitter = emitters.get(taskId);
        if (emitter == null) {
            return;
        }

        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("taskId", taskId, "error", error)));
            emitter.complete();
        } catch (IOException e) {
            log.debug("推送 SSE 错误失败 taskId={}", taskId);
        } finally {
            emitters.remove(taskId);
        }
    }
}
