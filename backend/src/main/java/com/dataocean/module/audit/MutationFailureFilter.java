package com.dataocean.module.audit;

import com.dataocean.common.security.IamPrincipal;
import com.dataocean.module.audit.entity.dto.AuditFailureDTO;
import com.dataocean.module.audit.service.AuditService;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 安全上下文建立后包裹实际请求，在原业务回滚后保存最小失败审计。
 */
@Slf4j
@RequiredArgsConstructor
public class MutationFailureFilter extends OncePerRequestFilter {

    /**
     * 只有这些实际业务资源进入管理失败审计。
     */
    private static final Set<String> RESOURCES = Set.of("users", "departments", "roles", "audit-records");

    /**
     * 对契约中的复合路径使用明确动作名称。
     */
    private static final Map<String, String> ACTIONS = Map.of(
            "password-reset",
            "password",
            "memberships",
            "transfer",
            "protected-roles",
            "assign",
            "secondary-members",
            "add-member",
            "member-transfers",
            "transfer-members",
            "child-transfers",
            "transfer-members");

    /**
     * 失败审计采用自己的短事务，与被拒绝的原业务事务分开。
     */
    private final AuditService audits;

    /**
     * 只记录已认证写入失败，匿名主体不冒充系统或某个人员。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            // 每次请求按权威账号和认证版本核验登录，结束由安全链清理身份。
            chain.doFilter(request, response);
        } finally {
            // 上层安全链稍后清理上下文，此处只能读取本次请求的可信主体。
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (response.getStatus() >= 400
                    && !request.getMethod().equals("GET")
                    && authentication != null
                    && authentication.getPrincipal() instanceof IamPrincipal) {
                // 每次请求按权威账号和认证版本核验登录，结束由安全链清理身份。
                var action = describe(request, response.getStatus());
                if (action != null) {
                    // 每次请求按权威账号和认证版本核验登录，结束由安全链清理身份。
                    try {
                        audits.failure(action);
                    } catch (DataAccessException error) {
                        // 原请求已拒绝，不会因失败审计存储故障放行业务。
                        log.error(
                                "失败审计无法持久化，action={}, status={}, errorType={}",
                                action.getActionCode(),
                                response.getStatus(),
                                error.getClass().getSimpleName());
                    }
                }
            }
        }
    }

    /**
     * 仅从固定路由提取动作和数字 ID，完全不读取秘密请求正文。
     */
    private AuditFailureDTO describe(HttpServletRequest request, int status) {
        String[] path = request.getRequestURI().split("/");
        if (path.length < 5
                || !path[1].equals("api")
                || !path[2].equals("v1")
                || !path[3].equals("iam")
                || !RESOURCES.contains(path[4])) {
            return null;
        }
        String resource = path[4];
        String id = path.length > 5 && path[5].matches("[1-9][0-9]{0,18}") ? path[5] : null;
        String action = path.length > 6
                ? ACTIONS.getOrDefault(path[6], path[6])
                : request.getMethod().equals("PUT")
                        ? "edit"
                        : request.getMethod().equals("DELETE") ? "delete" : "create";
        String target = resource.equals("users")
                ? "user"
                : resource.equals("roles") ? "role" : resource.equals("departments") ? "department" : "audit";
        return new AuditFailureDTO(resource.replace("audit-records", "audit") + "." + action, target, id, status);
    }
}
