package com.dataocean.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.user.entity.dto.UserCreateDTO;
import com.dataocean.module.user.entity.query.UserQuery;
import com.dataocean.module.user.entity.dto.UserUpdateDTO;
import com.dataocean.module.user.entity.vo.UserVO;
import com.dataocean.module.user.entity.SysDepartment;
import com.dataocean.module.user.entity.SysRole;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.entity.SysUserRole;
import com.dataocean.module.user.mapper.DepartmentMapper;
import com.dataocean.module.user.mapper.RoleMapper;
import com.dataocean.module.user.mapper.UserMapper;
import com.dataocean.module.user.mapper.UserRoleMapper;
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
import java.util.Set;
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
    private final RoleMapper roleMapper;
    private final DepartmentMapper departmentMapper;
    private final UserRoleMapper userRoleMapper;
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
     * 1. 校验用户名唯一性
     * 2. 校验部门有效性（存在且启用）
     * 3. 校验角色有效性（存在且启用）
     * 4. 创建用户记录（密码加密存储）
     * 5. 绑定用户-角色关联
     * </p>
     */
    @Transactional
    @Override
    public Long createUser(UserCreateDTO request) {
        log.info("开始创建用户 username={} departmentId={} roleIds={}",
                request.getUsername(), request.getDepartmentId(), request.getRoleIds());
        // 校验用户名唯一性
        ensureUsernameAvailable(request.getUsername());
        // 校验部门有效性
        validateDepartment(request.getDepartmentId());
        // 校验角色有效性
        validateRoles(request.getRoleIds());

        // 构建用户实体
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
        // 绑定用户角色关联
        bindRoles(user.getId(), request.getRoleIds());
        log.info("用户创建成功 userId={} username={} roleCount={}", user.getId(), user.getUsername(), request.getRoleIds().size());
        return user.getId();
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：
     * 1. 校验用户存在性
     * 2. 校验部门和角色有效性
     * 3. 更新用户基本信息
     * 4. 若传入 roleIds 则重新绑定角色（先删后插）
     * </p>
     */
    @Transactional
    @Override
    public void updateUser(Long id, UserUpdateDTO request) {
        log.info("开始更新用户 userId={} departmentId={} roleIds={}", id, request.getDepartmentId(), request.getRoleIds());
        // 校验用户存在性
        SysUser user = requireUser(id);
        // 校验部门有效性
        validateDepartment(request.getDepartmentId());
        // 校验角色有效性（仅在传入 roleIds 时）
        if (request.getRoleIds() != null) {
            validateRoles(request.getRoleIds());
        }
        // 更新用户基本信息
        if (StringUtils.hasText(request.getRealName())) {
            user.setRealName(request.getRealName());
        }
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setDepartmentId(request.getDepartmentId());
        userMapper.updateById(user);
        // 重新绑定角色关联
        if (request.getRoleIds() != null) {
            bindRoles(id, request.getRoleIds());
        }
        log.info("用户更新成功 userId={} username={}", id, user.getUsername());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：
     * 1. 禁止删除超级管理员（ID=1）
     * 2. 校验用户存在性
     * 3. 逻辑删除用户记录
     * 4. 清除用户角色关联
     * 5. 递增令牌版本号使已签发 JWT 失效
     * </p>
     */
    @Transactional
    @Override
    public void deleteUser(Long id) {
        log.info("开始删除用户 userId={}", id);
        // 超级管理员保护
        if (Long.valueOf(1L).equals(id)) {
            throw new BusinessException("不允许删除超级管理员");
        }
        requireUser(id);
        // 逻辑删除用户
        userMapper.deleteById(id);
        // 清除用户角色关联
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
        // 递增令牌版本号，使该用户已签发的 JWT 立即失效
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
     * 4. 批量查询关联的角色信息
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

        // 批量查询用户角色关联，避免 N+1 查询
        List<Long> userIds = users.stream().map(SysUser::getId).toList();
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getUserId, userIds));
        Set<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).collect(Collectors.toSet());
        Map<Long, SysRole> roleMap = roleIds.isEmpty() ? Map.of() :
                roleMapper.selectByIds(roleIds).stream()
                        .collect(Collectors.toMap(SysRole::getId, r -> r));
        // 按用户 ID 分组角色列表
        Map<Long, List<SysRole>> userRoleMap = userRoles.stream()
                .filter(ur -> roleMap.containsKey(ur.getRoleId()))
                .collect(Collectors.groupingBy(SysUserRole::getUserId,
                        Collectors.mapping(ur -> roleMap.get(ur.getRoleId()), Collectors.toList())));

        // 组装分页结果
        Page<UserVO> result = new Page<>(userPage.getCurrent(), userPage.getSize(), userPage.getTotal());
        result.setRecords(users.stream().map(user -> {
            List<SysRole> roles = userRoleMap.getOrDefault(user.getId(), List.of());
            return UserVO.builder()
                    .id(user.getId())
                    .username(user.getUsername())
                    .realName(user.getRealName())
                    .email(user.getEmail())
                    .phone(user.getPhone())
                    .departmentId(user.getDepartmentId())
                    .departmentName(user.getDepartmentId() == null ? null : deptNameMap.get(user.getDepartmentId()))
                    .roleIds(roles.stream().map(SysRole::getId).toList())
                    .roleNames(roles.stream().map(SysRole::getRoleName).toList())
                    .roleCodes(roles.stream().map(SysRole::getRoleCode).toList())
                    .status(user.getStatus())
                    .lastLoginAt(user.getLastLoginAt())
                    .createdAt(user.getCreatedAt())
                    .build();
        }).toList());
        return result;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：
     * 1. 校验用户存在性
     * 2. 超级管理员保护（不允许禁用/锁定）
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
        // 超级管理员保护
        if (Long.valueOf(1L).equals(id) && !Integer.valueOf(SysUser.STATUS_NORMAL).equals(status)) {
            throw new BusinessException("不允许禁用或锁定超级管理员");
        }
        // 校验目标状态合法性
        if (!List.of(SysUser.STATUS_NORMAL, SysUser.STATUS_DISABLED, SysUser.STATUS_LOCKED).contains(status)) {
            throw new BusinessException("用户状态不合法");
        }
        user.setStatus(status);
        userMapper.updateById(user);
        clearLoginLock(user.getUsername());
        // 禁用或锁定时需要使已签发 JWT 失效并清除失败计数
        if (Integer.valueOf(SysUser.STATUS_DISABLED).equals(status) || Integer.valueOf(SysUser.STATUS_LOCKED).equals(status)) {
            // 递增令牌版本号，使该用户已签发的 JWT 立即失效
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
     * 校验角色列表有效性（非空、存在且启用）。
     * <p>
     * 数据库不创建外键，角色关联有效性统一在业务层校验。
     * </p>
     *
     * @param roleIds 角色 ID 列表
     * @throws BusinessException 角色列表为空、角色不存在或已禁用时抛出
     */
    private void validateRoles(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            throw new BusinessException("至少选择一个角色");
        }
        // 数据库不创建外键，角色关联有效性统一在业务层校验
        List<SysRole> roles = roleMapper.selectByIds(roleIds);
        if (roles.size() != Set.copyOf(roleIds).size() || roles.stream().anyMatch(role -> !Integer.valueOf(1).equals(role.getStatus()))) {
            throw new BusinessException("角色不存在或已禁用");
        }
    }

    /**
     * 绑定用户角色关联（先删后插策略）。
     * <p>
     * 先删除该用户所有已有角色关联，再逐条插入新的关联记录。
     * </p>
     *
     * @param userId  用户 ID
     * @param roleIds 角色 ID 列表
     */
    private void bindRoles(Long userId, List<Long> roleIds) {
        log.debug("绑定用户角色 userId={} roleIds={}", userId, roleIds);
        // 先删除已有角色关联
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        // 逐条插入新的角色关联
        for (Long roleId : roleIds) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            userRoleMapper.insert(userRole);
        }
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
     * 查询关联的部门名称和角色列表，组装完整的 UserVO。
     * </p>
     *
     * @param user 用户实体
     * @return 用户视图对象
     */
    private UserVO toVO(SysUser user) {
        // 查询关联部门
        SysDepartment department = user.getDepartmentId() == null ? null : departmentMapper.selectById(user.getDepartmentId());
        // 查询关联角色列表
        List<SysRole> roles = roleMapper.selectByUserId(user.getId());
        return UserVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .departmentId(user.getDepartmentId())
                .departmentName(department == null ? null : department.getDeptName())
                .roleIds(roles.stream().map(SysRole::getId).toList())
                .roleNames(roles.stream().map(SysRole::getRoleName).toList())
                .roleCodes(roles.stream().map(SysRole::getRoleCode).toList())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
