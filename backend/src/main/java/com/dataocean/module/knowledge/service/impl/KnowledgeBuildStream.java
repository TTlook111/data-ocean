package com.dataocean.module.knowledge.service.impl;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.IamPrincipal;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.iam.service.StreamSessionPermission;
import com.dataocean.module.knowledge.service.KnowledgeBuildService;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Progress;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;

/**
 * 按当前会话发送在途构建的最新进度，重连仅回放快照。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBuildStream {

    /**
     * 短时有界连接，候选实际状态仍由平台库管理。
     */
    private final ConcurrentHashMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    /**
     * 复用进度来源及实时会话授权检查。
     */
    private final KnowledgeBuildService builds;

    /**
     * 用户失权时停止后续事件传输。
     */
    private final StreamSessionPermission sessions;

    /**
     * 打开或重连时先核对用户和源，然后发送当前阶段。
     */
    public SseEmitter open(long source, long candidate, String sessionId) {
        if (subscriptions.size() >= 128) {
            throw new BizIllegalException(ErrorCode.CAPACITY_EXCEEDED, "发布任务连接繁忙");
        }
        var actor = UserContext.current();
        sessions.require(sessionId, actor, "releases.view");
        var state = builds.progress(source, candidate);
        var emitter = new SseEmitter(45_000L);
        String id = UUID.randomUUID().toString();
        var subscription = new Subscription(source, candidate, sessionId, actor, emitter);
        emitter.onCompletion(() -> subscriptions.remove(id));
        emitter.onError(error -> subscriptions.remove(id));
        emitter.onTimeout(() -> subscriptions.remove(id));
        if ("BUILDING".equals(state.candidateState())) {
            subscriptions.put(id, subscription);
        }
        send(id, subscription, state);
        return emitter;
    }

    /**
     * 按确切候选状态变化发通知，不向订阅者提交任何业务写入。
     */
    @Scheduled(fixedDelay = 1000)
    void refresh() {
        subscriptions.forEach((id, sub) -> {
            try {
                // 后续推送使用原会话和实际当前角色。
                sessions.require(sub.sessionId, sub.actor, "releases.view");
                send(id, sub, builds.progress(sub.source, sub.candidate));
            } catch (RuntimeException error) {
                close(id, sub);
            }
        });
    }

    /**
     * 同一持久阶段只发一次，终态关闭连接并允许独立 GET 回读。
     */
    private void send(String id, Subscription sub, Progress current) {
        if (current.equals(sub.previous)) {
            return;
        }
        try {
            String request = current.buildId() == null ? Long.toString(sub.candidate) : current.buildId();
            String event = request + ":" + current.version() + ":" + current.buildState();
            sub.emitter.send(SseEmitter.event()
                    .id(event)
                    .name("knowledge-build.updated")
                    .data(new Event(event, request, 1, Instant.now(), current)));
            sub.previous = current;
            if (!"BUILDING".equals(current.candidateState())) {
                close(id, sub);
            }
        } catch (IOException | IllegalStateException unavailable) {
            subscriptions.remove(id);
        }
    }

    /**
     * 从调度中剔除已结束的短连接。
     */
    private void close(String id, Subscription sub) {
        subscriptions.remove(id);
        sub.emitter.complete();
    }

    /**
     * 事件关联确切构建代次；重连按原代次回放当前快照。
     */
    private record Event(String eventId, String requestId, int runVersion, Instant emittedAt, Progress payload) {}

    /**
     * 浏览器会话和候选标识随原订阅绑定。
     */
    @RequiredArgsConstructor
    private static class Subscription {
        private final long source;
        private final long candidate;
        private final String sessionId;
        private final IamPrincipal actor;
        private final SseEmitter emitter;
        private volatile Progress previous;
    }
}
