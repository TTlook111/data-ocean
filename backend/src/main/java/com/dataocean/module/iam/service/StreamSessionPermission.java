package com.dataocean.module.iam.service;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.IamPrincipal;
import com.dataocean.infrastructure.DataOceanProperties;
import com.dataocean.module.iam.security.CurrentIdentityFilter;

import java.time.Clock;
import java.util.Set;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 流式订阅复用 HTTP 会话时限和后台的当前用户资格，不延长会话。
 */
@Service
@RequiredArgsConstructor
public class StreamSessionPermission {

    /**
     * 当前会话的权威仓库。
     */
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    /**
     * 当前用户状态、认证版本和真实功能权限。
     */
    private final ServiceActorPermission actors;

    /**
     * 与 HTTP 登录态一致的有效期限。
     */
    private final DataOceanProperties properties;

    /**
     * 会话检查使用的时钟。
     */
    private final Clock clock;

    /**
     * 每轮发送前验证原会话仍属于同一主体并返回当前权限。
     */
    public Set<String> require(String sessionId, IamPrincipal actor, String... required) {
        Session session = sessions.findById(sessionId);
        if (session == null) {
            // 已退出或过期的会话直接关闭流。
            throw BizIllegalException.denied();
        }
        SecurityContext context =
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (context == null
                || context.getAuthentication() == null
                || !(context.getAuthentication().getPrincipal() instanceof IamPrincipal current)
                || current.userId() != actor.userId()
                || current.authenticationVersion() != actor.authenticationVersion()) {
            // 会话身份变化不能继承原订阅权限。
            throw BizIllegalException.denied();
        }
        Long authenticated = session.getAttribute(CurrentIdentityFilter.AUTH_AT);
        Long activity = session.getAttribute(CurrentIdentityFilter.ACTIVITY_AT);
        long now = clock.instant().getEpochSecond();
        if (authenticated == null
                || activity == null
                || now - authenticated >= properties.getSessionAbsoluteSeconds()
                || now - activity >= properties.getSessionIdleSeconds()) {
            // 长连接只读活动时间，不为用户续期。
            throw BizIllegalException.denied();
        }
        return actors.require(actor.userId(), actor.authenticationVersion(), required);
    }
}
