package com.dataocean.module.datasource.service.impl;

import com.dataocean.common.exception.CommonException;
import com.dataocean.common.security.IamPrincipal;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.DataOceanProperties;
import com.dataocean.module.datasource.entity.vo.CollectionDetailVO;
import com.dataocean.module.datasource.entity.vo.CollectionDetailVO.Content;
import com.dataocean.module.datasource.entity.vo.CollectionDetailVO.Counts;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.security.CurrentIdentityFilter;
import com.dataocean.module.iam.service.PermissionService;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;

/**
 * 从持久化任务事实发布有界状态流，后台主动重核每个订阅的真实登录与权限。
 */
@Service
@RequiredArgsConstructor
public class CollectionEventStream {

    /**
     * 本服务实例活跃的短时订阅。
     */
    private final ConcurrentHashMap<String, Subscriber> subscribers = new ConcurrentHashMap<>();

    /**
     * 与 HTTP 请求相同的权威会话仓库。
     */
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    /**
     * 查阅当前操作者和对应认证版本。
     */
    private final UserMapper users;

    /**
     * 每轮发送前重核任务查看权限。
     */
    private final PermissionService permissions;

    /**
     * 读取实际任务状态、冻结范围及版本。
     */
    private final CollectionServiceImpl collection;

    /**
     * 应用统一的会话空闲及绝对期限。
     */
    private final DataOceanProperties configuration;

    /**
     * UTC 服务时间。
     */
    private final Clock clock;

    /**
     * 限制流式连接数量并记下已核实的会话主体。
     */
    public SseEmitter open(String id, String sessionId) {
        CollectionDetailVO snapshot = collection.detail(id);
        if (subscribers.size() >= 128) {
            throw new CommonException(429502, 429, "采集事件连接繁忙，请稍后重试", null);
        }
        String key = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(45_000L);
        var subscriber = new Subscriber(emitter, sessionId, UserContext.current(), snapshot.taskId());
        subscribers.put(key, subscriber);
        emitter.onCompletion(() -> subscribers.remove(key));
        emitter.onTimeout(() -> subscribers.remove(key));
        emitter.onError(error -> subscribers.remove(key));
        // 所有新连接发送当前可见快照，适用于无游标、过期游标或跨代次重连。
        send(key, subscriber, snapshot);
        return emitter;
    }

    /**
     * 已退出、改密、失权或超时的连接在发送任何后续事实前完成关闭。
     */
    @Scheduled(fixedDelayString = "${dataocean.datasource.event-delay-ms:1000}")
    public void refresh() {
        subscribers.forEach((key, subscriber) -> {
            try {
                // 任一依赖故障也结束本订阅，禁止回退到陈旧资格。
                if (!permitted(subscriber)) {
                    close(key, subscriber);
                    return;
                }
                send(key, subscriber, collection.streamSnapshot(subscriber.task));
            } catch (RuntimeException error) {
                close(key, subscriber);
            }
        });
    }

    /**
     * 核验实际会话、认证版本、时限和当前页面权限，流式读取不续期。
     */
    private boolean permitted(Subscriber subscriber) {
        Session session = sessions.findById(subscriber.sessionId);
        if (session == null) {
            return false;
        }
        SecurityContext context =
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (context == null
                || context.getAuthentication() == null
                || !(context.getAuthentication().getPrincipal() instanceof IamPrincipal principal)
                || principal.userId() != subscriber.actor.userId()
                || principal.authenticationVersion() != subscriber.actor.authenticationVersion()) {
            return false;
        }
        Long authenticated = session.getAttribute(CurrentIdentityFilter.AUTH_AT);
        Long activity = session.getAttribute(CurrentIdentityFilter.ACTIVITY_AT);
        long now = clock.instant().getEpochSecond();
        if (authenticated == null
                || activity == null
                || now - authenticated >= configuration.getSessionAbsoluteSeconds()
                || now - activity >= configuration.getSessionIdleSeconds()) {
            return false;
        }
        var user = users.selectById(principal.userId());
        return user != null
                && "ENABLED".equals(user.getLifecycle())
                && !user.isMustChangePassword()
                && user.getAuthenticationVersion() == principal.authenticationVersion()
                && permissions.effective(principal.userId()).contains("collections.view");
    }

    /**
     * 只在状态变更时发送业务事件，终态收到后可通过原任务回读。
     */
    private void send(String key, Subscriber subscriber, CollectionDetailVO snapshot) {
        boolean first = subscriber.version < 0 || !snapshot.streamEpoch().equals(subscriber.epoch);
        boolean terminal = Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(snapshot.state());
        if (!first && subscriber.version == snapshot.version()) {
            return;
        }
        String name;
        Object content;
        String eventId = snapshot.streamEpoch() + ":" + snapshot.version();
        if (first) {
            name = "collection.snapshot";
            content = new Snapshot(snapshot, snapshot.streamEpoch(), eventId);
        } else if (terminal) {
            name = "collection.finished";
            content = new Finished(snapshot.state(), snapshot.content());
        } else if (snapshot.stopProgress() != null) {
            name = "task.cancellation";
            content = new Cancellation(
                    "UNKNOWN".equals(snapshot.executionState()) ? "VERIFYING" : "REQUESTED", snapshot.executionState());
        } else {
            name = "collection.progress";
            content = new Progress(
                    snapshot.content() == null ? null : snapshot.content().phase(),
                    snapshot.content() == null ? null : snapshot.content().counts());
        }
        try {
            // 显式事件名和值与 HTTP JSON 共享同一序列化规则。
            subscriber.emitter.send(SseEmitter.event()
                    .id(eventId)
                    .name(name)
                    .data(new Envelope(
                            snapshot.taskId(),
                            snapshot.streamEpoch(),
                            snapshot.version(),
                            clock.instant(),
                            name,
                            content)));
            subscriber.version = snapshot.version();
            subscriber.epoch = snapshot.streamEpoch();
            if (terminal) {
                close(key, subscriber);
            }
        } catch (IOException | IllegalStateException error) {
            close(key, subscriber);
        }
    }

    /**
     * 移除已结束或已失权的订阅。
     */
    private void close(String key, Subscriber subscriber) {
        subscribers.remove(key, subscriber);
        subscriber.emitter.complete();
    }

    /**
     * 一笔连接的身份、任务及已发送游标仅暂留内存。
     */
    private static final class Subscriber {

        /**
         * HTTP 流输出。
         */
        private final SseEmitter emitter;

        /**
         * 打开连接时实际的服务端会话。
         */
        private final String sessionId;

        /**
         * 当前经过认证的主体快照。
         */
        private final IamPrincipal actor;

        /**
         * 开始时核验的任务。
         */
        private final String task;

        /**
         * 上一次实际发送的状态版本。
         */
        private volatile int version = -1;

        /**
         * 上一次实际发送的流代次。
         */
        private volatile String epoch;

        /**
         * 记录已经授权的服务端连接定位。
         */
        Subscriber(SseEmitter emitter, String sessionId, IamPrincipal actor, String task) {
            this.emitter = emitter;
            this.sessionId = sessionId;
            this.actor = actor;
            this.task = task;
        }
    }

    /**
     * 流事件的公共信封。
     *
     * @param requestId 对应任务
     * @param streamEpoch 事件流代次
     * @param stateVersion 当前任务版本
     * @param emittedAt 发送时刻
     * @param eventType 稳定事件名
     * @param payload 明确类型的当前事件数据
     */
    private record Envelope(
            String requestId,
            String streamEpoch,
            int stateVersion,
            Instant emittedAt,
            String eventType,
            Object payload) {}

    /**
     * 新订阅可回读的状态。
     *
     * @param collection 当前可见任务
     * @param streamEpoch 当前代次
     * @param lastEventId 已输出的游标
     */
    private record Snapshot(CollectionDetailVO collection, String streamEpoch, String lastEventId) {}

    /**
     * 中间读取进度。
     *
     * @param phase 读取阶段
     * @param counts 真实进度
     */
    private record Progress(String phase, Counts counts) {}

    /**
     * 当前源端停止状态。
     *
     * @param status 请求或核实阶段
     * @param executionState 实际停止观察
     */
    private record Cancellation(String status, String executionState) {}

    /**
     * 任务结束状态及当前状态允许读取的内容。
     *
     * @param state 终态
     * @param content 实际结果
     */
    private record Finished(String state, Content content) {}
}
