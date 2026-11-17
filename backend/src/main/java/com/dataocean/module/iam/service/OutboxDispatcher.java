package com.dataocean.module.iam.service;

import com.dataocean.common.security.IamPrincipal;
import com.dataocean.infrastructure.DataOceanProperties;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.iam.entity.dto.AuthenticationInvalidatedDTO;
import com.dataocean.module.iam.entity.po.OutboxPO;
import com.dataocean.module.iam.mapper.OutboxMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 将已提交 Outbox 派发至 Redis，租约确认与本地消费分别防重。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatcher {

    /**
     * 已提交事件及领取租约的存储。
     */
    private final OutboxMapper outbox;

    /**
     * 事件流与会话失效的实际 Redis 连接。
     */
    private final StringRedisTemplate redis;

    /**
     * 按可信用户索引删除旧会话。
     */
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    /**
     * 类型化读取认证失效事件。
     */
    private final JsonCodec json;

    /**
     * 每次领取及确认的短事务。
     */
    private final TransactionTemplate transactions;

    /**
     * 调度开关与固定事件流配置。
     */
    private final DataOceanProperties properties;

    /**
     * 有界领取已到期事件，外部失败保留重投依据。
     */
    @Scheduled(fixedDelayString = "${dataocean.outbox-delay-ms:1000}")
    public void dispatch() {
        if (!properties.isOutboxEnabled()) {
            // 独立 API 验收进程可关闭派发，正式开发进程保持启用。
            return;
        }
        try {
            // 领取后释放数据库锁，避免网络等待占用业务事务。
            List<OutboxPO> pending = transactions.execute(status -> {
                // 选取到期记录和设置租约在同一短事务内完成。
                var rows = outbox.pending();
                for (var row : rows) {
                    // 每次接管使用新令牌，旧持有者不能确认本次租约。
                    String lease = UUID.randomUUID().toString();
                    outbox.claim(row.getEventId(), lease);
                    row.setLeaseToken(lease);
                }
                return rows;
            });
            for (var event : pending) {
                // 网络调用在领取事务外执行，每条失败独立登记重试。
                deliver(event);
            }
        } catch (DataAccessException error) {
            // 依赖失败由下次调度恢复，不改变已提交业务事实。
            log.warn("Outbox 存储暂不可用，errorType={}", error.getClass().getSimpleName());
        }
    }

    /**
     * 发布可重复事件，并由持有当前租约的进程确认送达。
     */
    private void deliver(OutboxPO event) {
        try {
            // 本地会话消费者先执行幂等失效，权威认证版本独立阻断旧会话。
            if (event.getEventType().equals("iam.authentication.invalidated")
                    && outbox.received(event.getEventId(), "SESSION_INVALIDATION") == 0) {
                var payload = json.read(event.getPayload(), AuthenticationInvalidatedDTO.class);
                sessions.findByPrincipalName(payload.getUserId()).values().forEach(session -> {
                    // 延迟或重投事件只结束旧版本会话，不能删除改密后新建立的登录。
                    Object stored =
                            session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                    if (stored instanceof SecurityContext context
                            && context.getAuthentication() != null
                            && context.getAuthentication().getPrincipal() instanceof IamPrincipal principal
                            && principal.authenticationVersion() < payload.getAuthenticationVersion()) {
                        // 仅删除早于事件认证版本的会话，保留同版及更新登录。
                        sessions.deleteById(session.getId());
                    }
                });
            }
            redis.opsForStream()
                    .add(
                            properties.getOutboxStream(),
                            Map.of(
                                    "eventId",
                                    event.getEventId(),
                                    "eventType",
                                    event.getEventType(),
                                    "payload",
                                    event.getPayload()));
            transactions.executeWithoutResult(status -> {
                // 旧进程迟到时不能确认已经转交的新租约。
                if (outbox.delivered(event.getEventId(), event.getLeaseToken()) == 1
                        && event.getEventType().equals("iam.authentication.invalidated")
                        && outbox.received(event.getEventId(), "SESSION_INVALIDATION") == 0) {
                    outbox.receipt(event.getEventId(), "SESSION_INVALIDATION");
                }
            });
        } catch (DataAccessException error) {
            // 已发布但确认失败允许重投，消费者依 eventId 去重。
            transactions.executeWithoutResult(status -> outbox.retry(event.getEventId(), event.getLeaseToken()));
            log.warn(
                    "事件派发等待重试，eventId={}, errorType={}",
                    event.getEventId(),
                    error.getClass().getSimpleName());
        }
    }
}
