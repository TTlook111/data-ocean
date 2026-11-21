package com.dataocean.module.iam.service.impl;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.iam.entity.po.PermissionPO;
import com.dataocean.module.iam.entity.vo.PermissionPageVO;
import com.dataocean.module.iam.entity.vo.PermissionVO;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.PermissionMapper;
import com.dataocean.module.iam.mapper.RoleAssignmentMapper;
import com.dataocean.module.iam.mapper.RoleMapper;
import com.dataocean.module.iam.service.PermissionService;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 计算当前角色使用权、逐动作授予上限和最后可用恢复途径。
 */
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    /**
     * 所属模块的持久化读写适配，不向接口直接返回 PO。
     */
    private final PermissionMapper mapper;

    /**
     * 跨组织与权限变更共用的配置锁和安全版本存储。
     */
    private final GuardMapper guard;

    /**
     * 当前角色、使用与授予集合及分配历史的访问依赖。
     */
    private final RoleMapper roles;

    /**
     * 查询当前角色分配关系。
     */
    private final RoleAssignmentMapper roleAssignments;

    /**
     * 合并当前有效角色的使用能力，不在会话保存永久权限快照。
     */
    public Set<String> effective(long userId) {
        return Set.copyOf(mapper.used(userId));
    }

    /**
     * 按同一管理动作合并角色明确授予集合，不能用使用权代替。
     */
    public Set<String> grantable(long userId, String operation) {
        return Set.copyOf(mapper.grantable(userId, operation));
    }

    /**
     * 同时检查当前动作及其显式所属页面权。
     */
    public boolean has(String code) {
        Set<String> effective = effective(UserContext.getUser());
        return code != null && effective.contains(code) && permits(mapper.selectById(code), effective);
    }

    /**
     * 核对已持有的候选权限仍启用，且该动作的显式页面也在当前使用权中。
     */
    private boolean permits(PermissionPO permission, Set<String> effective) {
        return permission != null
                && permission.isEnabled()
                && (permission.getPagePermissionCode() == null
                        || effective.contains(permission.getPagePermissionCode()));
    }

    /**
     * 当前动作或显式所属页面不满足时抛出明确权限拒绝。
     */
    public void require(String code) {
        if (!has(code)) {
            throw BizIllegalException.denied();
        }
    }

    /**
     * 要求至少一个明确配置入口具备当前实际资格。
     */
    public void requireAny(String... codes) {
        Set<String> effective = effective(UserContext.getUser());
        var candidates = Arrays.stream(codes)
                .filter(Objects::nonNull)
                .filter(effective::contains)
                .distinct()
                .toList();
        if (candidates.isEmpty()
                || mapper.selectByIds(candidates).stream().noneMatch(permission -> permits(permission, effective))) {
            // 空候选直接拒绝，跳过数据库读取。
            throw BizIllegalException.denied();
        }
    }

    /**
     * 依据系统目录检查受保护项，不从功能名称推断。
     */
    public boolean hasProtectedCodes(Collection<String> codes) {
        return !codes.isEmpty() && mapper.selectByIds(codes).stream().anyMatch(PermissionPO::isProtected);
    }

    /**
     * 检查人员保留的当前角色是否涉及受保护能力，包含停用角色。
     */
    public boolean protectedUser(long id) {
        return mapper.protectedUser(id) > 0;
    }

    /**
     * 检查角色完整使用及授予集合是否包含受保护能力。
     */
    public boolean protectedRole(long id) {
        return mapper.protectedRole(id) > 0;
    }

    /**
     * 批量读取受保护角色，空范围直接返回空集合。
     */
    @Override
    public Set<Long> protectedRoles(Collection<Long> roleIds) {
        if (roleIds.isEmpty()) {
            // 没有指定角色时无需读取功能目录。
            return Set.of();
        }

        return Set.copyOf(mapper.protectedRoles(roleIds));
    }

    /**
     * 批量核验目录项有效性及动作所属页面的完整选择。
     */
    public void validateCodes(List<String> codes) {
        Set<String> selected = new HashSet<>(codes);
        if (selected.size() != codes.size()) {
            // 同一目录项只能选择一次。
            throw BizIllegalException.invalid("功能选择不能重复");
        }
        var directory = selected.isEmpty() ? List.<PermissionPO>of() : mapper.selectByIds(selected);
        if (directory.size() != selected.size() || directory.stream().anyMatch(permission -> !permission.isEnabled())) {
            // 拒绝未知或已停用的目录项。
            throw BizIllegalException.invalid("存在不可用功能");
        }
        if (directory.stream()
                .map(PermissionPO::getPagePermissionCode)
                .filter(Objects::nonNull)
                .anyMatch(page -> !selected.contains(page))) {
            // 所有动作都必须带上目录中归属的页面。
            throw BizIllegalException.invalid("操作必须同时具有所属页面访问权");
        }
    }

    /**
     * 按同一管理动作核对完整实际能力是否落在当前可授予集合内。
     */
    public void checkGrant(List<String> codes, String operation) {
        // 只合并实际具有当前管理动作的角色上限，不能跨角色拼接使用权与授予资格。
        if (!grantable(UserContext.getUser(), operation).containsAll(codes)) {
            throw new BizIllegalException(ErrorCode.GRANT_LIMIT_EXCEEDED, "所选完整功能超出当前操作的可授予上限");
        }
    }

    /**
     * 需要按同一管理动作核对授予上限的功能集合。
     */
    private static final Set<String> GRANTING_OPERATIONS = Set.of(
            "users.create",
            "users.assign",
            "users.enable",
            "users.recover",
            "roles.create",
            "roles.edit",
            "roles.enable",
            "roles.protected");

    /**
     * 比较角色变更前后操作者的完整授予资格，阻止自我提权。
     */
    public void checkSelfRoleChange(long roleId, List<String> permissions, List<String> grants, String lifecycle) {
        var before = actorRoles();
        if (before.stream().noneMatch(role -> role.id == roleId)) {
            return;
        }
        var after = before.stream()
                .map(role -> role.id == roleId
                        ? new AuthorityRole(roleId, permissions, grants, lifecycle.equals("ENABLED"))
                        : role)
                .toList();
        rejectSelfIncrease(before, after);
    }

    /**
     * 核对个人角色调整不会提高操作者自己的管理上限。
     */
    public void checkSelfAssignment(List<RoleVersionRefVO> selected) {
        var after = selected.stream()
                .map(ref -> authority(Ids.parse(ref.getRoleId())))
                .toList();
        rejectSelfIncrease(actorRoles(), after);
    }

    /**
     * 读取当前操作者真实持有的角色，用于计算变更前后的管理上限。
     */
    private List<AuthorityRole> actorRoles() {
        return roleAssignments.assignments(UserContext.getUser()).stream()
                .map(a -> authority(a.getRoleId()))
                .toList();
    }

    /**
     * 将角色当前使用、授予和启用状态组成管理资格依据。
     */
    private AuthorityRole authority(long id) {
        var role = roles.selectById(id);
        return new AuthorityRole(
                id,
                roles.permissions(id),
                roles.grantable(id),
                role.getLifecycle().equals("ENABLED"));
    }

    /**
     * 按当前对象版本原子更新业务资料并递增内容修订。
     */
    private Map<String, Set<String>> profile(List<AuthorityRole> items) {
        Map<String, Set<String>> result = new HashMap<>();
        for (var role : items) {
            if (role.enabled) {
                for (String operation : GRANTING_OPERATIONS) {
                    if (role.permissions.contains(operation)) {
                        result.computeIfAbsent(operation, key -> new HashSet<>())
                                .addAll(role.grants);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 比较变更前后各管理动作的授予集合，拒绝提高操作者自身上限。
     */
    private void rejectSelfIncrease(List<AuthorityRole> before, List<AuthorityRole> after) {
        var previous = profile(before);
        for (var entry : profile(after).entrySet()) {
            if (!previous.getOrDefault(entry.getKey(), Set.of()).containsAll(entry.getValue())) {
                throw new BizIllegalException(ErrorCode.GRANT_LIMIT_EXCEEDED, "本次变更会提高你自己的可授予上限，请由其他已有资格人员办理");
            }
        }
    }

    /**
     * 用于比较管理动作资格与授予集合的不可变角色快照。
     */
    @Getter
    @AllArgsConstructor
    private static class AuthorityRole {

        /**
         * 该记录的稳定标识，对外长整型以十进制字符串表达。
         */
        private final long id;

        /**
         * 该角色或视图明确包含的功能使用代码集合。
         */
        private final List<String> permissions;

        /**
         * 当前角色明确配置的可授予能力集合。
         */
        private final List<String> grants;

        /**
         * 该角色当前是否为正常启用状态。
         */
        private final boolean enabled;
    }

    /**
     * 在配置锁内检查变更后仍至少存在一条实际可用恢复途径。
     */
    public void ensureRecovery() {
        if (!guard.selectById(1).isBootstrapCompleted()) {
            // 仅在受控初始化完成前允许暂时没有恢复途径。
            return;
        }
        Set<String> required = Set.of("users.view", "users.recover");
        for (long candidate : mapper.recoveryCandidates()) {
            // 页面与操作可以来自多个有效角色，授予依据必须属于同一恢复动作。
            if (!new HashSet<>(mapper.used(candidate)).containsAll(required)) {
                continue;
            }
            Set<String> grants = new HashSet<>(mapper.grantable(candidate, "users.recover"));
            Set<String> restorable = new HashSet<>();
            for (var role : roles.all()) {
                // 只有完整使用与授予内容均在现有上限内的正常角色才能计入恢复图。
                if (!role.getLifecycle().equals("ENABLED")) {
                    continue;
                }
                List<String> used = roles.permissions(role.getId());
                Set<String> complete = new HashSet<>(used);
                complete.addAll(roles.grantable(role.getId()));
                if (grants.containsAll(complete)) {
                    restorable.addAll(used);
                }
            }
            if (restorable.containsAll(required)) {
                return;
            }
        }
        throw new BizIllegalException(ErrorCode.IAM_LAST_RECOVERY_PATH, "至少保留一名可正常登录且具备完整恢复资格的人员");
    }

    /**
     * 按显式页面归属组装功能目录，不从权限前缀推断授权。
     */
    public List<PermissionPageVO> catalog() {
        List<PermissionPO> all = mapper.all();
        return all.stream()
                .filter(p -> p.getPermissionKind().equals("PAGE"))
                .map(page -> new PermissionPageVO(
                        page.getCode(),
                        page.getModuleCode(),
                        page.getName(),
                        all.stream()
                                .filter(p -> p.getCode().equals(page.getCode())
                                        || page.getCode().equals(p.getPagePermissionCode()))
                                .map(p -> new PermissionVO(
                                        p.getCode(),
                                        p.getPermissionKind().equals("PAGE") ? "访问页面" : p.getName(),
                                        p.getPurpose(),
                                        p.isProtected(),
                                        p.isEnabled(),
                                        p.getPagePermissionCode()))
                                .toList()))
                .toList();
    }
}
