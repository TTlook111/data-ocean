package com.dataocean.module.query.service.impl;

import com.dataocean.module.query.entity.vo.QueryTaskVO;
import com.dataocean.module.query.service.IamS1QueryNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/** SSE 仅交付已保护的数据；每个浏览器连接独立注册、清理，不缓存查询结果。 */
@Service
@Slf4j
public class IamS1QueryNotificationServiceImpl implements IamS1QueryNotificationService {
    private final Map<String, Set<SseEmitter>> subscriptions = new ConcurrentHashMap<>();

    @Override
    public SseEmitter subscribe(String taskId, Long permissionRevision) {
        // 同任务的不同标签页各自保留订阅；原子更新避免末个连接清理与新订阅竞争。
        SseEmitter emitter = new SseEmitter(120_000L);
        subscriptions.compute(taskId, (id, current) -> {
            Set<SseEmitter> connected = current == null ? new CopyOnWriteArraySet<>() : current;
            connected.add(emitter);
            return connected;
        });

        // 回调只移除触发它的连接，迟到的旧连接回调不能关闭任何新连接。
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(error -> remove(taskId, emitter));
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of(
                    "taskId", taskId, "protocolVersion", "IAM-SIMPLE-1",
                    "permissionRevision", permissionRevision)));
        } catch (IOException | IllegalStateException ex) {
            // 建连失败只清理本连接；其他页面仍可接收通知并通过任务读取核对状态。
            remove(taskId, emitter);
            log.debug("S1 SSE 建立连接失败 taskId={}", taskId);
        }
        return emitter;
    }

    @Override
    public void sendResult(String taskId, QueryTaskVO result) {
        // 完成通知不会修改数据库终态，也不保存供后续连接重放的结果副本。
        send(taskId, "result", result, true);
    }

    @Override
    public void sendProgress(String taskId, QueryTaskVO progress) {
        // 进度是尽力交付的页面反馈，发送失败不能影响查询执行。
        send(taskId, "progress", progress, false);
    }

    @Override
    public void sendError(String taskId, String message) {
        // 保留现有前端事件协议；错误消息不携带未经保护的结果载荷。
        send(taskId, "error", Map.of(
                "taskId", taskId, "protocolVersion", "IAM-SIMPLE-1", "status", "FAILED",
                "error", message == null || message.isBlank() ? "S1 查询失败" : message), true);
    }

    private void send(String taskId, String event, Object payload, boolean terminal) {
        // CopyOnWriteArraySet 使本轮交付拥有稳定连接快照，允许回调或新订阅并发发生。
        Set<SseEmitter> connected = subscriptions.get(taskId);
        if (connected == null) return;
        for (SseEmitter emitter : connected) {
            try {
                emitter.send(SseEmitter.event().name(event).data(payload));
                if (terminal) emitter.complete();
            } catch (IOException | IllegalStateException ex) {
                // 单个连接断开不会阻止其他标签页接收同一条事件。
                remove(taskId, emitter);
                log.debug("S1 SSE 通知失败 taskId={} event={}", taskId, event);
            } finally {
                // 只释放本次已交付终态的连接，不能移除发送过程中新增的订阅。
                if (terminal) remove(taskId, emitter);
            }
        }
    }

    private void remove(String taskId, SseEmitter emitter) {
        // 清理与注册使用同一键的原子计算，避免空集合被删除时丢掉新加入的连接。
        subscriptions.computeIfPresent(taskId, (id, connected) -> {
            connected.remove(emitter);
            return connected.isEmpty() ? null : connected;
        });
    }
}
