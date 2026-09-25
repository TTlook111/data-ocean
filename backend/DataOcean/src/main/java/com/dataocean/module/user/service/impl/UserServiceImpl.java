package com.dataocean.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1DataGrant;
import com.dataocean.module.permission.s1.entity.IamS1UserRole;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserRoleMapper;
import com.dataocean.module.permission.s1.service.IamS1AuditEventService;
import com.dataocean.module.permission.s1.service.IamS1PermissionRevisionService;
import com.dataocean.module.user.entity.dto.UserCreateDTO;
import com.dataocean.module.user.entity.query.UserQuery;
import com.dataocean.module.user.entity.dto.UserUpdateDTO;
import com.dataocean.module.user.entity.vo.UserVO;
import com.dataocean.module.user.entity.SysDepartment;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.DepartmentMapper;
import com.dataocean.module.user.mapper.UserMapper;
import com.dataocean.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 用户管理业务实现类。
 * <p>
 * 实现 {@link UserService} 接口，提供用户 CRUD、状态变更、密码重置等完整管理功能。
 * 所有写操作在事务中执行，涉及角色/部门的关联校验在业务层完成（不依赖数据库外键）。
 * 用户删除、禁用、锁定和密码重置时会通过 Redis 令牌版本号机制使已签发 JWT 立即失效。
 * </p>
 *
 * @author DataOcean
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;
    private final IamS1UserRoleMapper iamS1UserRoleMapper;
    private final IamS1DataGrantMapper iamS1DataGrantMapper;
    private final IamS1PermissionRevisionService revisionService;
    private final IamS1AuditEventService auditEventService;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate stringRedisTemplate;

    /** 安全随机数生成器，用于生成临时密码 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /** 临时密码可用字母字符集（排除易混淆字符 I/l/O/0） */
    private static final char[] TEMP_PASSWORD_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    /** 临时密码可用数字字符集（排除易混淆字符 0/1） */
    private static final char[] TEMP_PASSWORD_DIGITS = "23456789".toCharArray();
    /** 临时密码完整字符集（字母+数字） */
    private static final char[] TEMP_PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final String FAILED_LOGIN_PREFIX = "login:fail:";
    private static final String AUTO_LOCK_MARKER_PREFIX = "login:auto-lock:";
    private static final String AUTO_LOCK_TTL_PREFIX = "login:auto-lock:ttl:";

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：
     * 1. 拒绝非空旧 roleIds（S1 角色绑定只走独立接口）
     * 2. 校验用户名唯一性
     * 3. 校验部门有效性（存在且启用）
     * 4. 创建用户记录（密码加密存储），不建立任何角色权限事实
     * </p>
     */
    @Transactional
    @Override
    public Long createUser(UserCreateDTO request) {
        log.info("开始创建用户 username={} departmentId={}", request.getUsername(), request.getDepartmentId());
        rejectLegacyRoleIds(request.getRoleIds());
        // 校验用户名唯一性
        ensureUsernameAvailable(request.getUsername());
        // 校验部门有效性
        validateDepartment(request.getDepartmentId());

        // 构建用户实体。不建立任何角色权限事实：S1 角色绑定只走 /api/iam-s1/users/{id}/roles。
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setPasswordChanged(0);
        user.setRealName(request.getRealName());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setDepartmentId(request.getDepartmentId());
        user.setStatus(SysUser.STATUS_NORMAL);
        user.setDeleted(0);
        userMapper.insert(user);
        log.info("用户创建成功 userId={} username={}", user.getId(), user.getUsername());
        return user.getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：
     * 1. 拒绝非空旧 roleIds
     * 2. 校验用户存在性
     * 3. 校验部门有效性
     * 4. 更新用户基本信息，不写入任何旧角色权限事实
     * </p>
     */
    @Transactional
    @Override
    public void updateUser(Long id, UserUpdateDTO request) {
        log.info("开始更新用户 userId={} departmentId={}", id, request.getDepartmentId());
        rejectLegacyRoleIds(request.getRoleIds());
        // 校验用户存在性
        SysUser user = requireUser(id);
        // 校验部门有效性
        validateDepartment(request.getDepartmentId());
        boolean departmentChanged = !Objects.equals(user.getDepartmentId(), request.getDepartmentId());
        // 更新用户基本信息。不写入任何旧角色权限事实。
        if (StringUtils.hasText(request.getRealName())) {
            user.setRealName(request.getRealName());
        }
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setDepartmentId(request.getDepartmentId());
        userMapper.updateById(user);
        if (departmentChanged) {
            recordOrganizationRevision("USER", id, "DEPARTMENT_CHANGE",
                    "userId=" + id + ";departmentId=" + request.getDepartmentId(),
                    "调整用户主部门");
        }
        log.info("用户更新成功 userId={} username={}", id, user.getUsername());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 同一事务内：锁定该用户 S1 绑定 → 最后管理员保护 → 停用 ACTIVE 绑定并撤回直接用户 grant
     * → 记录 S1 revision/审计 → 再逻辑删除用户。不依赖旧 PermissionChangedEvent。
     * </p>
     */
    @Transactional
    @Override
    public void deleteUser(Long id) {
        log.info("开始删除用户 userId={}", id);
        requireUser(id);
        List<IamS1UserRole> bindings = iamS1UserRoleMapper.selectByUserIdForUpdate(id);
        ensureNotLastProtectedAdmin(id, "删除");
        Long operatorId = operatorId();
        long disabledCount = countActive(bindings);
        Long revisionNo = revisionService.record("USER", id, "DELETE", operatorId, "删除用户并停用其 S1 绑定");
        disableS1BindingsAndRevokeUserGrants(id, bindings, revisionNo, operatorId);
        auditEventService.recordSuccess("USER_DELETE", operatorId, "USER", id, null,
                "userId=" + id + ";disabledBindings=" + disabledCount + ";revision=" + revisionNo,
                "删除用户并停用其 S1 绑定", UUID.randomUUID().toString());
        userMapper.deleteById(id);
        stringRedisTemplate.opsForValue().increment(tokenVersionKey(id));
        log.info("用户删除成功 userId={}", id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public UserVO getUserById(Long id) {
        return toVO(requireUser(id));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：
     * 1. 构建动态查询条件（用户名/姓名模糊匹配，部门/状态精确匹配）
     * 2. 分页查询用户列表
     * 3. 批量查询关联的部门名称
     * 4. 不回填旧 sys_role，避免把旧角色显示成新权限角色
     * 5. 组装 UserVO 返回
     * </p>
     */
    @Override
    public Page<UserVO> listUsers(UserQuery request) {
        log.debug("查询用户列表 username={} realName={} departmentId={} status={} page={} pageSize={}",
                request.getUsername(), request.getRealName(), request.getDepartmentId(), request.getStatus(),
                request.resolvedPage(), request.resolvedPageSize());
        // 构建动态查询条件
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .like(StringUtils.hasText(request.getUsername()), SysUser::getUsername, request.getUsername())
                .like(StringUtils.hasText(request.getRealName()), SysUser::getRealName, request.getRealName())
                .eq(request.getDepartmentId() != null, SysUser::getDepartmentId, request.getDepartmentId())
                .eq(request.getStatus() != null, SysUser::getStatus, request.getStatus())
                .orderByDesc(SysUser::getCreatedAt);
        // 执行分页查询
        Page<SysUser> userPage = userMapper.selectPage(new Page<>(request.resolvedPage(), request.resolvedPageSize()), wrapper);

        List<SysUser> users = userPage.getRecords();
        if (users.isEmpty()) {
            Page<UserVO> result = new Page<>(userPage.getCurrent(), userPage.getSize(), userPage.getTotal());
            result.setRecords(List.of());
            return result;
        }

        // 批量查询部门名称，避免 N+1 查询
        Set<Long> deptIds = users.stream().map(SysUser::getDepartmentId).filter(id -> id != null).collect(Collectors.toSet());
        Map<Long, String> deptNameMap = deptIds.isEmpty() ? Map.of() :
                departmentMapper.selectByIds(deptIds).stream()
                        .collect(Collectors.toMap(SysDepartment::getId, SysDepartment::getDeptName));

        // 组装分页结果。角色字段固定为空：S1 角色请走 /api/iam-s1/users/{id}/roles。
        Page<UserVO> result = new Page<>(userPage.getCurrent(), userPage.getSize(), userPage.getTotal());
        result.setRecords(users.stream().map(user -> UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .departmentId(user.getDepartmentId())
                .departmentName(user.getDepartmentId() == null ? null : deptNameMap.get(user.getDepartmentId()))
                .roleIds(List.of())
                .roleNames(List.of())
                .roleCodes(List.of())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build()).toList());
        return result;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：
     * 1. 校验用户存在性
     * 2. 保护最后一个有效 S1 系统管理员（不允许禁用/锁定）
     * 3. 校验目标状态合法性
     * 4. 更新状态
     * 5. 禁用/锁定时清除登录失败计数并使 JWT 失效
     * </p>
     */
    @Transactional
    @Override
    public void updateStatus(Long id, Integer status) {
        SysUser user = requireUser(id);
        Integer oldStatus = user.getStatus();
        if (!Integer.valueOf(SysUser.STATUS_NORMAL).equals(status)) {
            ensureNotLastProtectedAdmin(id, "禁用或锁定");
        }
        // 校验目标状态合法性
        if (!List.of(SysUser.STATUS_NORMAL, SysUser.STATUS_DISABLED, SysUser.STATUS_LOCKED).contains(status)) {
            throw new BusinessException("用户状态不合法");
        }
        user.setStatus(status);
        userMapper.updateById(user);
        clearLoginLock(user.getUsername());
        if (!Objects.equals(oldStatus, status)) {
            String changeType = Integer.valueOf(SysUser.STATUS_NORMAL).equals(status) ? "ENABLE"
                    : Integer.valueOf(SysUser.STATUS_LOCKED).equals(status) ? "LOCK" : "DISABLE";
            recordOrganizationRevision("USER", id, changeType,
                    "userId=" + id + ";oldStatus=" + oldStatus + ";newStatus=" + status,
                    "变更用户状态");
        }
        // 禁用或锁定时需要使已签发 JWT 失效并清除失败计数
        if (Integer.valueOf(SysUser.STATUS_DISABLED).equals(status) || Integer.valueOf(SysUser.STATUS_LOCKED).equals(status)) {
            stringRedisTemplate.opsForValue().increment(tokenVersionKey(id));
        }
        log.info("用户状态更新成功 userId={} username={} oldStatus={} newStatus={}",
                id, user.getUsername(), oldStatus, status);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：
     * 1. 校验用户存在性
     * 2. 生成随机临时密码
     * 3. 加密存储并标记为未修改密码状态
     * 4. 清除登录失败计数
     * 5. 递增令牌版本号使已签发 JWT 失效
     * </p>
     */
    @Transactional
    @Override
    public String resetPassword(Long id) {
        log.info("开始重置用户密码 userId={}", id);
        SysUser user = requireUser(id);
        // 生成随机临时密码
        String tempPassword = generateTempPassword();
        // 加密存储并标记为未修改密码
        user.setPasswordHash(passwordEncoder.encode(tempPassword));
        user.setPasswordChanged(0);
        userMapper.updateById(user);
        // 清除登录失败计数
        stringRedisTemplate.delete(failedLoginKey(user.getUsername()));
        // 递增令牌版本号，使已签发 JWT 失效
        stringRedisTemplate.opsForValue().increment(tokenVersionKey(id));
        log.info("用户密码重置成功，已刷新令牌版本 userId={} username={}", id, user.getUsername());
        return tempPassword;
    }

    /**
     * 根据 ID 查询用户，不存在则抛出业务异常。
     *
     * @param id 用户 ID
     * @return 用户实体
     * @throws BusinessException 用户不存在时抛出
     */
    private SysUser requireUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            log.warn("用户查询失败：用户不存在 userId={}", id);
            throw new BusinessException("用户不存在");
        }
        return user;
    }

    /**
     * 校验用户名是否可用（唯一性检查）。
     *
     * @param username 待校验的用户名
     * @throws BusinessException 用户名已存在时抛出
     */
    private void ensureUsernameAvailable(String username) {
        SysUser existing = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (existing != null) {
            throw new BusinessException("用户名已存在");
        }
    }

    /**
     * 校验部门有效性（存在且启用）。
     * <p>
     * 数据库不创建外键，部门关联有效性统一在业务层校验。
     * </p>
     *
     * @param departmentId 部门 ID，为 null 时跳过校验
     * @throws BusinessException 部门不存在或已禁用时抛出
     */
    private void validateDepartment(Long departmentId) {
        if (departmentId == null) {
            return;
        }
        // 数据库不创建外键，部门关联有效性统一在业务层校验
        SysDepartment department = departmentMapper.selectById(departmentId);
        if (department == null || !Integer.valueOf(1).equals(department.getStatus())) {
            throw new BusinessException("部门不存在或已禁用");
        }
    }

    /**
     * 新用户接口拒绝非空旧 roleIds。空列表或未传表示「这里不绑定角色」，允许通过。
     */
    private void rejectLegacyRoleIds(List<Long> roleIds) {
        if (roleIds != null && !roleIds.isEmpty()) {
            throw new BusinessException(400, "用户角色请在「角色与负责源」中绑定，不能通过旧 roleIds 写入");
        }
    }

    /**
     * 删除或禁用前保护最后一个仍可登录的 S1 系统管理员。
     * 锁定全部有效受保护绑定后再数当前状态为正常的账号，不能只拦固定 id=1。
     */
    private void ensureNotLastProtectedAdmin(Long userId, String action) {
        List<IamS1UserRole> bindings = iamS1UserRoleMapper.selectActiveProtectedBindingsForUpdate();
        Set<Long> adminUserIds = bindings.stream()
                .map(IamS1UserRole::getUserId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        if (!adminUserIds.contains(userId)) {
            return;
        }
        Map<Long, SysUser> admins = adminUserIds.isEmpty() ? Map.of()
                : userMapper.selectByIds(adminUserIds).stream()
                .collect(Collectors.toMap(SysUser::getId, user -> user, (a, b) -> a));
        long enabledCount = adminUserIds.stream()
                .map(admins::get)
                .filter(user -> user != null && Integer.valueOf(SysUser.STATUS_NORMAL).equals(user.getStatus()))
                .count();
        SysUser target = admins.get(userId);
        boolean currentlyEnabled = target != null && Integer.valueOf(SysUser.STATUS_NORMAL).equals(target.getStatus());
        long remaining = currentlyEnabled ? enabledCount - 1 : enabledCount;
        if (remaining < 1) {
            throw new BusinessException("不能" + action + "最后一个有效 S1 系统管理员");
        }
    }

    /**
     * 停用该用户全部仍生效的 S1 角色绑定，并把直接用户 grant 标为 REVOKED。
     * 行保留为历史事实（B6 不删 S1 表）；Resolver 只读 ACTIVE/ENABLED。
     */
    private void disableS1BindingsAndRevokeUserGrants(Long userId, List<IamS1UserRole> bindings,
                                                     Long revisionNo, Long operatorId) {
        if (bindings != null) {
            for (IamS1UserRole binding : bindings) {
                if (binding == null || !Integer.valueOf(IamS1Constants.ENABLED).equals(binding.getStatus())) {
                    continue;
                }
                binding.setStatus(IamS1Constants.DISABLED);
                binding.setRevisionNo(revisionNo);
                binding.setUpdatedBy(operatorId);
                iamS1UserRoleMapper.updateById(binding);
            }
        }
        List<IamS1DataGrant> grants = iamS1DataGrantMapper.selectActiveBySubjectForUpdate(
                IamS1Constants.PROTOCOL_VERSION, IamS1Constants.SUBJECT_USER, userId);
        if (grants != null) {
            for (IamS1DataGrant grant : grants) {
                grant.setStatus(IamS1Constants.DATA_GRANT_STATUS_REVOKED);
                grant.setRevisionNo(revisionNo);
                grant.setUpdatedBy(operatorId);
                iamS1DataGrantMapper.updateById(grant);
            }
        }
    }

    private void recordOrganizationRevision(String targetType, Long targetId, String changeType,
                                            String afterSummary, String reason) {
        Long operatorId = operatorId();
        Long revisionNo = revisionService.record(targetType, targetId, changeType, operatorId, reason);
        auditEventService.recordSuccess(targetType + "_" + changeType, operatorId, targetType, targetId,
                null, afterSummary + ";revision=" + revisionNo, reason, UUID.randomUUID().toString());
    }

    private Long operatorId() {
        try {
            return UserContext.currentUserId();
        } catch (BusinessException ignored) {
            return null;
        }
    }

    private static long countActive(List<IamS1UserRole> bindings) {
        if (bindings == null) {
            return 0;
        }
        return bindings.stream()
                .filter(binding -> binding != null && Integer.valueOf(IamS1Constants.ENABLED).equals(binding.getStatus()))
                .count();
    }

    /**
     * 构建用户令牌版本号的 Redis Key。
     * <p>
     * 令牌版本号用于实现 JWT 即时失效：签发时记录版本号到 JWT，
     * 校验时比对 Redis 中的当前版本号，不一致则拒绝。
     * </p>
     *
     * @param userId 用户 ID
     * @return Redis Key 字符串
     */
    private String tokenVersionKey(Long userId) {
        return "user:token-version:" + userId;
    }

    private String failedLoginKey(String username) {
        return FAILED_LOGIN_PREFIX + username;
    }

    private String autoLockMarkerKey(String username) {
        return AUTO_LOCK_MARKER_PREFIX + username;
    }

    private String autoLockTtlKey(String username) {
        return AUTO_LOCK_TTL_PREFIX + username;
    }

    private void clearLoginLock(String username) {
        stringRedisTemplate.delete(failedLoginKey(username));
        stringRedisTemplate.delete(autoLockTtlKey(username));
        stringRedisTemplate.delete(autoLockMarkerKey(username));
    }

    /**
     * 生成随机临时密码。
     * <p>
     * 密码长度 8 位，保证至少包含一个字母和一个数字，
     * 使用 Fisher-Yates 洗牌算法打乱字符顺序。
     * 排除易混淆字符（I/l/O/0/1）以提高可读性。
     * </p>
     *
     * @return 随机生成的临时密码明文
     */
    private String generateTempPassword() {
        char[] password = new char[8];
        // 确保至少包含一个字母
        password[0] = TEMP_PASSWORD_LETTERS[SECURE_RANDOM.nextInt(TEMP_PASSWORD_LETTERS.length)];
        // 确保至少包含一个数字
        password[1] = TEMP_PASSWORD_DIGITS[SECURE_RANDOM.nextInt(TEMP_PASSWORD_DIGITS.length)];
        // 剩余位置随机填充字母或数字
        for (int i = 2; i < password.length; i++) {
            password[i] = TEMP_PASSWORD_CHARS[SECURE_RANDOM.nextInt(TEMP_PASSWORD_CHARS.length)];
        }
        // Fisher-Yates 洗牌算法打乱字符顺序
        for (int i = password.length - 1; i > 0; i--) {
            int j = SECURE_RANDOM.nextInt(i + 1);
            char temp = password[i];
            password[i] = password[j];
            password[j] = temp;
        }
        return new String(password);
    }

    /**
     * 将用户实体转换为视图对象。
     * <p>
     * 查询关联的部门名称。角色字段固定为空，避免把旧 sys_role 显示成新权限角色。
     * </p>
     *
     * @param user 用户实体
     * @return 用户视图对象
     */
    private UserVO toVO(SysUser user) {
        // 查询关联部门
        SysDepartment department = user.getDepartmentId() == null ? null : departmentMapper.selectById(user.getDepartmentId());
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .departmentId(user.getDepartmentId())
                .departmentName(department == null ? null : department.getDeptName())
                .roleIds(List.of())
                .roleNames(List.of())
                .roleCodes(List.of())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
