package com.dataocean.module.iam.service.impl;

import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.iam.entity.po.PermissionPO;
import com.dataocean.module.iam.entity.vo.PermissionPageVO;
import com.dataocean.module.iam.entity.vo.PermissionVO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.PermissionMapper;
import com.dataocean.module.iam.mapper.RoleMapper;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import com.dataocean.module.iam.service.PermissionService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class PermissionServiceImpl implements PermissionService {
    private final PermissionMapper mapper;
    private final GuardMapper guard;
    private final RoleMapper roles;
    public PermissionServiceImpl(PermissionMapper mapper, GuardMapper guard,RoleMapper roles) { this.mapper=mapper; this.guard=guard; this.roles=roles; }
    public Set<String> effective(long userId) { return Set.copyOf(mapper.used(userId)); }
    public Set<String> grantable(long userId,String operation) { return Set.copyOf(mapper.grantable(userId,operation)); }
    public boolean has(String code) {
        Set<String> effective=effective(UserContext.getUser());
        PermissionPO permission=mapper.all().stream().filter(p->p.getCode().equals(code) && p.isEnabled()).findFirst().orElse(null);
        return permission!=null && effective.contains(code) && (permission.getPagePermissionCode()==null || effective.contains(permission.getPagePermissionCode()));
    }
    public void require(String code) { if (!has(code)) throw BizIllegalException.denied(); }
    public void requireAny(String... codes) { for (String code:codes) if (has(code)) return; throw BizIllegalException.denied(); }
    public boolean protectedCode(String code) { return mapper.all().stream().anyMatch(p->p.getCode().equals(code) && p.isProtected()); }
    public boolean protectedUser(long id) { return mapper.protectedUser(id)>0; }
    public boolean protectedRole(long id) { return mapper.protectedRole(id)>0; }
    public void validateCodes(List<String> codes, boolean allowProtected) {
        if (new HashSet<>(codes).size()!=codes.size()) throw BizIllegalException.invalid("功能选择不能重复");
        List<PermissionPO> directory=mapper.all();
        for (String code:codes) {
            PermissionPO permission=directory.stream().filter(p->p.getCode().equals(code) && p.isEnabled()).findFirst().orElseThrow(()->BizIllegalException.invalid("存在不可用功能"));
            if (!allowProtected && permission.isProtected()) throw new BizIllegalException(ErrorCode.PROTECTED_FLOW_REQUIRED,"受保护管理能力须单独核验");
            if (permission.getPagePermissionCode()!=null && !codes.contains(permission.getPagePermissionCode())) throw BizIllegalException.invalid("操作必须同时具有所属页面访问权");
        }
    }
    public void checkGrant(List<String> codes,String operation) {
        // 只合并实际具有当前管理动作的角色上限，不能跨角色拼接使用权与授予资格。
        if (!grantable(UserContext.getUser(),operation).containsAll(codes)) throw new BizIllegalException(ErrorCode.GRANT_LIMIT_EXCEEDED,"所选完整功能超出当前操作的可授予上限");
    }
    private static final Set<String> GRANTING_OPERATIONS=Set.of("users.create","users.assign","users.enable","users.recover","roles.create","roles.edit","roles.enable","roles.protected");
    public void checkSelfRoleChange(long roleId,List<String> permissions,List<String> grants,String lifecycle) {
        var before=actorRoles();
        if(before.stream().noneMatch(role->role.id==roleId))return;
        var after=before.stream().map(role->role.id==roleId?new AuthorityRole(roleId,permissions,grants,lifecycle.equals("ENABLED")):role).toList();
        rejectSelfIncrease(before,after);
    }
    public void checkSelfAssignment(List<RoleVersionRefVO> selected) {
        var after=selected.stream().map(ref->authority(com.dataocean.common.Ids.parse(ref.getRoleId()))).toList();
        rejectSelfIncrease(actorRoles(),after);
    }
    private List<AuthorityRole> actorRoles() { return roles.assignments(UserContext.getUser()).stream().map(a->authority(a.getRoleId())).toList(); }
    private AuthorityRole authority(long id) {
        var role=roles.find(id);
        return new AuthorityRole(id,roles.permissions(id),roles.grantable(id),role.getLifecycle().equals("ENABLED"));
    }
    private Map<String,Set<String>> profile(List<AuthorityRole> items) {
        Map<String,Set<String>> result=new HashMap<>();
        for(var role:items)if(role.enabled)for(String operation:GRANTING_OPERATIONS)if(role.permissions.contains(operation))
            result.computeIfAbsent(operation,key->new HashSet<>()).addAll(role.grants);
        return result;
    }
    private void rejectSelfIncrease(List<AuthorityRole> before,List<AuthorityRole> after) {
        var previous=profile(before);
        for(var entry:profile(after).entrySet())if(!previous.getOrDefault(entry.getKey(),Set.of()).containsAll(entry.getValue()))
            throw new BizIllegalException(ErrorCode.GRANT_LIMIT_EXCEEDED,"本次变更会提高你自己的可授予上限，请由其他已有资格人员办理");
    }
    @Getter
    @AllArgsConstructor
    private static class AuthorityRole {
        private final long id;
        private final List<String> permissions;
        private final List<String> grants;
        private final boolean enabled;
    }
    public void ensureRecovery() {
        if (guard.current().isBootstrapCompleted() && mapper.recoveryCount()<1)
            throw new BizIllegalException(ErrorCode.IAM_LAST_RECOVERY_PATH,"至少保留一名已完成改密、可实际使用恢复能力的人员");
    }
    public List<PermissionPageVO> catalog() {
        List<PermissionPO> all=mapper.all();
        return all.stream().filter(p->p.getPermissionKind().equals("PAGE")).map(page->new PermissionPageVO(page.getCode(),page.getModuleCode(),page.getName(),
            all.stream().filter(p->p.getCode().equals(page.getCode()) || page.getCode().equals(p.getPagePermissionCode()))
                .map(p->new PermissionVO(p.getCode(),p.getName(),p.getPurpose(),p.isProtected(),p.isEnabled(),p.getPagePermissionCode())).toList())).toList();
    }
}
