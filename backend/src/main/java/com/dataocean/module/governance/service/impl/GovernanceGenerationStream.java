package com.dataocean.module.governance.service.impl;

import com.dataocean.common.exception.CommonException;
import com.dataocean.common.security.IamPrincipal;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.governance.service.GovernanceGenerationService;
import com.dataocean.module.governance.service.GovernanceGenerationService.StreamState;
import com.dataocean.module.iam.service.StreamSessionPermission;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 发布持久任务的轻量状态通知，重连直接回放最新可用状态。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GovernanceGenerationStream {

    /**
     * 单实例的短时订阅，任务进度仍由平台库持久化。
     */
    private final ConcurrentHashMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    /**
     * 读取权威任务事实。
     */
    private final GovernanceGenerationService tasks;

    /**
     * 后台发送前校验当前会话和权限。
     */
    private final StreamSessionPermission sessions;

    /**
     * 事件时间使用平台时钟。
     */
    private final Clock clock;

    /**
     * 先核权再登记订阅，始终发送最新状态以容忍历史游标丢失。
     */
    public SseEmitter open(long source, String request, String sessionId) {
        var actor = UserContext.current();
        var state = tasks.streamState(
                source, request, actor.userId(), sessions.require(sessionId, actor, "governance.view"));
        if (subscriptions.size() >= 128) {
            // 使用有界活跃连接，避免任务长期重连耗尽服务资源。
            throw new CommonException(429502, 429, "治理任务连接繁忙，请稍后重试", null);
        }
        var key = UUID.randomUUID().toString();
        var emitter = new SseEmitter(45_000L);
        var subscription = new Subscription(source, request, sessionId, actor, emitter);
        subscriptions.put(key, subscription);
        emitter.onCompletion(() -> subscriptions.remove(key));
        emitter.onTimeout(() -> subscriptions.remove(key));
        emitter.onError(error -> subscriptions.remove(key));
        send(key, subscription, state);
        return emitter;
    }

    /**
     * 核对每条活跃订阅的实时资格及数据库版本，再按变化发送。
     */
    @Scheduled(fixedDelay = 1000)
    public void refresh() {
        subscriptions.forEach((key, subscription) -> {
            try {
                // 失权和任务不可读时直接关闭，不回退到已缓存的进度。
                var rights = sessions.require(subscription.sessionId, subscription.actor, "governance.view");
                send(
                        key,
                        subscription,
                        tasks.streamState(
                                subscription.source, subscription.request, subscription.actor.userId(), rights));
            } catch (RuntimeException error) {
                // 记录关闭原因类型，避免输出会话标识或原始异常内容。
                log.debug(
                        "治理任务订阅关闭，requestId={}，原因={}",
                        subscription.request,
                        error.getClass().getSimpleName());
                subscriptions.remove(key);
                subscription.emitter.complete();
            }
        });
    }

    /**
     * 发送公共任务信封，终态送达后允许客户端从详情回读结果。
     */
    private void send(String key, Subscription subscription, StreamState state) {
        if (subscription.version == state.version()) {
            return;
        }
        var name = subscription.version < 0 ? "generation.snapshot" : "generation.updated";
        try {
            // 治理任务不复用可执行代次，批次编号本身作为订阅代次。
            subscription.emitter.send(SseEmitter.event()
                    .id(subscription.request + ":" + state.version())
                    .name(name)
                    .data(new Envelope(
                            subscription.request,
                            subscription.request,
                            state.version(),
                            clock.instant(),
                            name,
                            state)));
            subscription.version = state.version();
            if (Set.of("SUCCEEDED", "PARTIAL_FAILED", "FAILED").contains(state.state())) {
                // 终态无需继续占用长连接。
                subscriptions.remove(key);
                subscription.emitter.complete();
            }
        } catch (IOException | IllegalStateException error) {
            // 容器负责完成断开的 HTTP 请求。
            subscriptions.remove(key);
        }
    }

    /**
     * 分离 HTTP 订阅上下文与持久化任务状态。
     */
    @RequiredArgsConstructor
    private static class Subscription {

        /**
         * 只能在打开订阅时授权的数据源。
         */
        private final long source;

        /**
         * 需要关注的批次或独立任务。
         */
        private final String request;

        /**
         * 原会话标识，只留在内存。
         */
        private final String sessionId;

        /**
         * 打开时认证的真实主体。
         */
        private final IamPrincipal actor;

        /**
         * HTTP 流输出。
         */
        private final SseEmitter emitter;

        /**
         * 最新已传输的版本，尚未发布时为负数。
         */
        private volatile int version = -1;
    }

    /**
     * SSE 公共业务信封，只包含任务状态和游标。
     *
     * @param requestId 任务编号
     * @param streamEpoch 有效订阅代次
     * @param stateVersion 已持久状态版本
     * @param emittedAt 发送时间
     * @param eventType 通知类型
     * @param payload 最小可披露状态
     */
    private record Envelope(
            String requestId,
            String streamEpoch,
            int stateVersion,
            Instant emittedAt,
            String eventType,
            StreamState payload) {}
}
