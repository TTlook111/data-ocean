package com.dataocean.module.iam.service;

import com.dataocean.module.iam.entity.vo.PermissionPageVO;
import java.util.List;
import java.util.Set;

public interface PermissionService {
    Set<String> effective(long userId);
    Set<String> grantable(long userId,String operation);
    void require(String code);
    void requireAny(String... codes);
    boolean has(String code);
    boolean protectedCode(String code);
    boolean protectedUser(long userId);
    boolean protectedRole(long roleId);
    void validateCodes(List<String> codes, boolean allowProtected);
    void checkGrant(List<String> codes,String operation);
    void checkSelfRoleChange(long roleId,List<String> permissions,List<String> grants,String lifecycle);
    void checkSelfAssignment(List<com.dataocean.module.iam.entity.vo.RoleVersionRefVO> roles);
    void ensureRecovery();
    List<PermissionPageVO> catalog();
}
