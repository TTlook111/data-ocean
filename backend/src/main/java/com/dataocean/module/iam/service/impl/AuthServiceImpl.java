package com.dataocean.module.iam.service.impl;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.IamPrincipal;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.DataOceanProperties;
import com.dataocean.infrastructure.SecretKeyProvider;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.iam.entity.dto.ChangePasswordDTO;
import com.dataocean.module.iam.entity.dto.InitialPasswordDTO;
import com.dataocean.module.iam.entity.dto.LoginDTO;
import com.dataocean.module.iam.entity.po.CredentialPO;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.CurrentSessionVO;
import com.dataocean.module.iam.mapper.CredentialMapper;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.AuthService;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PasswordPolicy;
import com.dataocean.module.iam.service.PermissionService;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import jakarta.annotation.PostConstruct;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 将验密、限流与认证版本重核分开，避免长时间持有组织配置锁。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /**
     * 原子累计登录尝试并设置固定窗口，依赖故障时不降级放行。
     */
    private static final DefaultRedisScript<Long> ATTEMPT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end; return n",
            Long.class);

    /**
     * 人员与凭据的权威存储。
     */
    private final UserMapper users;

    /**
     * 按人员主键访问独立的凭据记录。
     */
    private final CredentialMapper credentials;

    /**
     * 认证及权限变更的串行化边界。
     */
    private final GuardMapper guard;

    /**
     * 已配置的安全散列实现。
     */
    private final PasswordEncoder encoder;

    /**
     * 登录频率的跨进程计数。
     */
    private final StringRedisTemplate redis;

    /**
     * 登录窗口及会话时限配置。
     */
    private final DataOceanProperties properties;

    /**
     * 登录名限流键的受控 HMAC。
     */
    private final SecretKeyProvider secrets;

    /**
     * 明确包裹短时数据库事务。
     */
    private final TransactionTemplate transactions;

    /**
     * 读取当前功能并检查恢复途径。
     */
    private final PermissionService permissions;

    /**
     * 本人改密的同事务成功审计。
     */
    private final AuditService audits;

    /**
     * 写入可靠认证失效事件。
     */
    private final MutationService mutations;

    /**
     * 未知账号使用同样编码器的预计算散列，避免额外编码造成明显响应差异。
     */
    private String dummyHash;

    /**
     * 应用装配时准备一次虚拟凭据，内容不进入日志或外部响应。
     */
    @PostConstruct
    void prepareDummyCredential() {
        dummyHash = encoder.encode("unavailable-credential");
    }

    /**
     * 限制尝试次数，验密后重核状态与修订，防止重置期间旧密码登录。
     */
    @Override
    public IamPrincipal authenticate(LoginDTO data, String remoteAddress) {
        String username = data.getUsername().trim();
        // 用户名及来源分别限流，键中不保存可逆登录信息。
        limit("user:" + username, properties.getLoginAttemptLimit());
        limit("address:" + remoteAddress, properties.getLoginAttemptLimit() * 10);
        var candidate = users.byUsername(username);
        CredentialPO credential = candidate == null ? null : credentials.selectById(candidate.getId());
        // 未知账号也执行散列计算，统一返回可披露的凭据错误。
        boolean matched =
                encoder.matches(data.getPassword(), credential == null ? dummyHash : credential.getPasswordHash());
        if (candidate == null
                || credential == null
                || !matched
                || !candidate.getLifecycle().equals("ENABLED")) {
            throw new BizIllegalException(ErrorCode.AUTH_INVALID_CREDENTIALS, "登录名或密码不正确");
        }
        IamPrincipal principal = transactions.execute(status -> {
            // 与重置、停用串行；验证期间发生的任何凭据变化均拒绝。
            guard.shared();
            var current = users.lock(candidate.getId());
            var latest = credentials.selectById(candidate.getId());
            if (!current.getLifecycle().equals("ENABLED")
                    || current.getAuthenticationVersion() != candidate.getAuthenticationVersion()
                    || latest.getCredentialRevision() != credential.getCredentialRevision()) {
                throw new BizIllegalException(ErrorCode.AUTH_INVALID_CREDENTIALS, "登录名或密码不正确");
            }
            users.firstLogin(current.getId(), current.getAuthenticationVersion());
            return new IamPrincipal(
                    current.getId(),
                    current.getAuthenticationVersion(),
                    current.getUsername(),
                    current.getDisplayName());
        });
        log.info("登录认证成功，userId={}", principal.userId());
        return principal;
    }

    /**
     * 在 Redis 中执行原子窗口计数，超限统一拒绝。
     */
    private void limit(String identity, long maximum) {
        String key = "dataocean:login:" + HexFormat.of().formatHex(secrets.hmac(identity));
        Long attempts = redis.execute(ATTEMPT, List.of(key), Long.toString(properties.getLoginWindowSeconds()));
        if (attempts == null || attempts > maximum) {
            throw new BizIllegalException(ErrorCode.RATE_LIMITED, "登录尝试较多，请稍后重试");
        }
    }

    /**
     * 待改密身份不取得业务权限，时限使用首次认证时刻计算。
     */
    @Override
    public CurrentSessionVO current(IamPrincipal principal, long authenticatedAt) {
        return transactions.execute(status -> {
            // 当前用户资料和权限版本在同一读取边界内组成响应。
            guard.shared();
            var user = users.selectById(principal.userId());
            if (user == null
                    || !user.getLifecycle().equals("ENABLED")
                    || user.getAuthenticationVersion() != principal.authenticationVersion()) {
                // 读取当前权威状态或可信登录主体，缺失时不回退默认身份。
                throw new BizIllegalException(ErrorCode.AUTH_EXPIRED, "登录已失效，请重新登录");
            }
            return new CurrentSessionVO(
                    Long.toString(user.getId()),
                    user.getUsername(),
                    user.getDisplayName(),
                    user.getVersion(),
                    user.isMustChangePassword() ? "PASSWORD_CHANGE_REQUIRED" : "AUTHENTICATED",
                    user.isMustChangePassword(),
                    Instant.ofEpochSecond(authenticatedAt + properties.getSessionAbsoluteSeconds()),
                    user.isMustChangePassword()
                            ? List.of()
                            : permissions.effective(user.getId()).stream()
                                    .sorted()
                                    .toList(),
                    Long.toString(guard.selectById(1).getSecurityVersion()));
        });
    }

    /**
     * 首次改密沿用受限身份，禁止在普通完整会话重复调用。
     */
    @Override
    public void initialPassword(InitialPasswordDTO data) {
        updatePassword(data.getExpectedVersion(), null, data.getNewPassword(), data.getConfirmPassword(), true);
    }

    /**
     * 本人正常改密必须验证当前密码，成功后结束所有旧会话资格。
     */
    @Override
    public void changePassword(ChangePasswordDTO data) {
        updatePassword(
                data.getExpectedVersion(),
                data.getCurrentPassword(),
                data.getNewPassword(),
                data.getConfirmPassword(),
                false);
    }

    /**
     * 验密及新散列在事务外完成，提交时锁后再次核对版本。
     */
    private void updatePassword(
            int version, String currentPassword, String nextPassword, String confirmation, boolean initial) {
        PasswordPolicy.requireStrong(nextPassword);
        if (!Objects.equals(nextPassword, confirmation)) {
            throw BizIllegalException.invalid("两次新密码不一致");
        }
        long id = UserContext.getUser();
        var credential = credentials.selectById(id);
        if (!initial && !encoder.matches(currentPassword, credential.getPasswordHash())) {
            throw new BizIllegalException(ErrorCode.IAM_CURRENT_PASSWORD_INVALID, "原密码不正确");
        }
        if (encoder.matches(nextPassword, credential.getPasswordHash())) {
            throw new BizIllegalException(ErrorCode.IAM_PASSWORD_UNCHANGED, "新密码不能与原密码相同");
        }
        String encoded = encoder.encode(nextPassword);
        transactions.executeWithoutResult(status -> {
            // guard 保证改密和最后恢复途径检查不能被其他管理写穿插。
            guard.lock();
            var user = users.lock(id);
            if (user.getVersion() != version
                    || credentials.selectById(id).getCredentialRevision() != credential.getCredentialRevision()) {
                throw BizIllegalException.conflict();
            }
            if (user.getAuthenticationVersion() != UserContext.current().authenticationVersion()
                    || !user.getLifecycle().equals("ENABLED")) {
                throw new BizIllegalException(ErrorCode.AUTH_EXPIRED, "登录已失效，请重新登录");
            }
            if (user.isMustChangePassword() != initial) {
                throw BizIllegalException.state("当前账号不适用此改密入口");
            }
            credentials.password(id, encoded);
            users.invalidate(id, false);
            if (!guard.selectById(1).isBootstrapCompleted()) {
                // 空库初始化的唯一例外在首次必要改密后永久关闭。
                guard.completeBootstrap();
            }
            permissions.ensureRecovery();
            mutations.invalidateAuthentication(id, "PASSWORD_CHANGED");
            audits.record(AuditWriteDTO.builder()
                    .actionCode(initial ? "auth.password.initial" : "account.password")
                    .targetType("user")
                    .targetId(Long.toString(id))
                    .targetName(user.getDisplayName())
                    .changes(List.of(new ChangeVO("credential", "登录凭据", null, "已变更")))
                    .beforeRoles(List.of())
                    .afterRoles(List.of())
                    .securityVersion(guard.selectById(1).getSecurityVersion())
                    .build());
        });
        log.info("本人密码更新完成，userId={}", id);
    }
}
