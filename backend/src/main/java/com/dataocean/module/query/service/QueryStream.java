package com.dataocean.module.query.service;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.IamPrincipal;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.iam.service.StreamSessionPermission;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;

/**
 * 向原浏览器会话发送真实持久化进度，断线可以从快照继续。
 */
@Service
@RequiredArgsConstructor
public class QueryStream {

    /** 并发连接数量有界，正文通过独立鉴权的详情端点读取。 */
    private final ConcurrentHashMap<String, Subscription> listeners = new ConcurrentHashMap<>();
    /** 使用真实请求状态和当前来源授权。 */
    private final QueryService queries;
    /** 后续事件保持同一有效 HTTP 会话。 */
    private final StreamSessionPermission sessions;

    /** 打开连接须验证用户、来源和原会话。 */
    public SseEmitter open(String request, String session) {
        if (listeners.size() >= 128) {
            throw BizIllegalException.state("问数进度连接繁忙，请稍后重试");
        }
        var actor = UserContext.current();
        sessions.require(session, actor, "query.view");
        var initial = queries.progress(request, actor.userId());
        var emitter = new SseEmitter(45000L);
        var id = UUID.randomUUID().toString();
        var listener = new Subscription(request, session, actor, emitter);
        emitter.onCompletion(() -> listeners.remove(id));
        emitter.onTimeout(() -> listeners.remove(id));
        emitter.onError(error -> listeners.remove(id));
        send(id, listener, initial);
        if (!initial.terminal()) {
            listeners.put(id, listener);
        }
        return emitter;
    }

    /** 后续事件实时鉴权，失权时关闭并让页面按详情重新校验。 */
    @Scheduled(fixedDelay = 750)
    void tick() {
        listeners.forEach((id, listener) -> {
            try {
                sessions.require(listener.session, listener.actor, "query.view");
                send(id, listener, queries.progress(listener.request, listener.actor.userId()));
            } catch (RuntimeException stopped) {
                close(id, listener);
            }
        });
    }

    /** 用同一个事实修订形成幂等游标，重连只回当前状态。 */
    private void send(String id, Subscription listener, QueryService.Progress next) {
        if (next.equals(listener.previous)) {
            return;
        }
        try {
            String event =
                    next.id() + ":" + next.runVersion() + ":" + next.updatedAt().toEpochMilli() + ":" + next.state()
                            + ":" + next.explanationRevision() + ":" + next.explanationStatus();
            listener.emitter.send(
                    SseEmitter.event().id(event).name("query.updated").data(next));
            listener.previous = next;
            if (next.terminal()) {
                close(id, listener);
            }
        } catch (IOException | IllegalStateException disconnected) {
            close(id, listener);
        }
    }

    /** 回收断线和终态订阅。 */
    private void close(String id, Subscription listener) {
        listeners.remove(id);
        listener.emitter.complete();
    }

    /** 权限随原会话验证，每次发送只保留上一已发进度。 */
    @RequiredArgsConstructor
    private static class Subscription {
        /** 原请求编号。 */
        private final String request;
        /** Redis 服务端会话编号，仅用于校验。 */
        private final String session;
        /** 原可信登录主体。 */
        private final IamPrincipal actor;
        /** 当前 SSE 连接。 */
        private final SseEmitter emitter;
        /** 最近一次实际发送的状态。 */
        private volatile QueryService.Progress previous;
    }
}
