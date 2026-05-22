package com.dataocean.module.user.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.user.entity.query.UserQuery;
import com.dataocean.module.user.entity.dto.UserCreateDTO;
import com.dataocean.module.user.entity.dto.UserUpdateDTO;
import com.dataocean.module.user.entity.vo.UserVO;

/**
 * 用户管理业务接口。
 * <p>
 * 提供用户的创建、修改、删除、查询、状态变更和密码重置等管理功能。
 * 所有写操作均在事务中执行，并包含业务规则校验（如用户名唯一性、角色/部门有效性等）。
 * </p>
 *
 * @author DataOcean
 */
public interface UserService {

    /**
     * 创建新用户。
     * <p>
     * 校验用户名唯一性、部门有效性和角色有效性后，创建用户并绑定角色。
     * 新用户默认标记为未修改密码状态（passwordChanged=0）。
     * </p>
     *
     * @param request 用户创建请求参数（含用户名、密码、角色列表等）
     * @return 新创建用户的 ID
     */
    Long createUser(UserCreateDTO request);

    /**
     * 更新用户信息。
     * <p>
     * 可更新姓名、邮箱、手机、部门和角色绑定关系。
     * 若传入 roleIds 则重新绑定角色（先删后插）。
     * </p>
     *
     * @param id      用户 ID
     * @param request 用户更新请求参数
     */
    void updateUser(Long id, UserUpdateDTO request);

    /**
     * 删除用户（逻辑删除）。
     * <p>
     * 超级管理员（ID=1）不允许删除。删除后同时清除用户角色关联并使已签发 JWT 失效。
     * </p>
     *
     * @param id 用户 ID
     */
    void deleteUser(Long id);

    /**
     * 根据 ID 获取用户详情。
     *
     * @param id 用户 ID
     * @return 用户视图对象（含部门名称、角色列表等）
     */
    UserVO getUserById(Long id);

    /**
     * 分页查询用户列表。
     * <p>
     * 支持按用户名、姓名模糊搜索，按部门和状态精确过滤。
     * 返回结果包含每个用户的部门名称和角色信息。
     * </p>
     *
     * @param request 分页查询参数（含筛选条件）
     * @return 分页用户视图对象列表
     */
    Page<UserVO> listUsers(UserQuery request);

    /**
     * 更新用户状态（启用/禁用/锁定）。
     * <p>
     * 超级管理员不允许被禁用或锁定。禁用/锁定时会使该用户已签发的 JWT 立即失效。
     * </p>
     *
     * @param id     用户 ID
     * @param status 目标状态值（1=正常, 2=禁用, 3=锁定）
     */
    void updateStatus(Long id, Integer status);

    /**
     * 重置用户密码。
     * <p>
     * 生成随机临时密码，标记为未修改密码状态，清除登录失败计数并使已签发 JWT 失效。
     * </p>
     *
     * @param id 用户 ID
     * @return 生成的临时密码明文（仅此次返回，不持久化明文）
     */
    String resetPassword(Long id);
}
