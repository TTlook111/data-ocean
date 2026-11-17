package com.dataocean.module.iam.service.impl;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.iam.entity.dto.CreateUserDTO;
import com.dataocean.module.iam.entity.dto.CreateUserPreviewDTO;
import com.dataocean.module.iam.entity.dto.EnableUserDTO;
import com.dataocean.module.iam.entity.dto.MembershipSelectionDTO;
import com.dataocean.module.iam.entity.dto.NewUserProfileDTO;
import com.dataocean.module.iam.entity.dto.ResetPasswordDTO;
import com.dataocean.module.iam.entity.dto.RoleAssignmentDTO;
import com.dataocean.module.iam.entity.dto.TransferUserDTO;
import com.dataocean.module.iam.entity.dto.UpdateContactDTO;
import com.dataocean.module.iam.entity.dto.UpdateUserDTO;
import com.dataocean.module.iam.entity.dto.UserArchivePreviewDTO;
import com.dataocean.module.iam.entity.dto.UserDisablePreviewDTO;
import com.dataocean.module.iam.entity.dto.UserEnablePreviewDTO;
import com.dataocean.module.iam.entity.dto.UserImpactPreviewDTO;
import com.dataocean.module.iam.entity.dto.UserProtectedRolesPreviewDTO;
import com.dataocean.module.iam.entity.dto.UserResetPasswordPreviewDTO;
import com.dataocean.module.iam.entity.dto.UserTransferPreviewDTO;
import com.dataocean.module.iam.entity.dto.UserUpdatePreviewDTO;
import com.dataocean.module.iam.entity.dto.VersionActionDTO;
import com.dataocean.module.iam.entity.po.UserPO;
import com.dataocean.module.iam.entity.query.DepartmentOptionQuery;
import com.dataocean.module.iam.entity.query.RoleOptionQuery;
import com.dataocean.module.iam.entity.query.UserOptionQuery;
import com.dataocean.module.iam.entity.query.UserQuery;
import com.dataocean.module.iam.entity.vo.AccountVO;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.DepartmentOptionVO;
import com.dataocean.module.iam.entity.vo.DepartmentRefVO;
import com.dataocean.module.iam.entity.vo.ImpactCountVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.RoleOptionVO;
import com.dataocean.module.iam.entity.vo.RoleRefVO;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;
import com.dataocean.module.iam.entity.vo.UserDetailVO;
import com.dataocean.module.iam.entity.vo.UserOptionVO;
import com.dataocean.module.iam.entity.vo.UserRowVO;
import com.dataocean.module.iam.mapper.DepartmentMapper;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.MembershipMapper;
import com.dataocean.module.iam.mapper.RoleMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.AssignmentService;
import com.dataocean.module.iam.service.IamViewAssembler;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PasswordPolicy;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ReviewService;
import com.dataocean.module.iam.service.UserService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.hutool.core.bean.BeanUtil;
import lombok.RequiredArgsConstructor;

/**
 * 协调人员资料、组织归属、生命周期和历史恢复的原子变更。
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /**
     * 人员资料、生命周期与受控凭据的访问依赖。
     */
    private final UserMapper users;

    /**
     * 部门树和聚合版本的持久化访问依赖。
     */
    private final DepartmentMapper departments;

    /**
     * 主兼任关系及其结束历史的持久化能力。
     */
    private final MembershipMapper memberships;

    /**
     * 当前角色、使用与授予集合及分配历史的访问依赖。
     */
    private final RoleMapper roles;

    /**
     * 跨组织与权限变更共用的配置锁和安全版本存储。
     */
    private final GuardMapper guard;

    /**
     * 将当前业务事实组装为明确安全返回对象的能力。
     */
    private final IamViewAssembler views;

    /**
     * 当前操作者实际使用能力与显式授予资格的业务服务。
     */
    private final PermissionService permissions;

    /**
     * 主兼任及角色分配的共用业务能力。
     */
    private final AssignmentService assignments;

    /**
     * 防重、配置锁、审计及 Outbox 的业务提交边界。
     */
    private final MutationService mutations;

    /**
     * 服务端完整影响核对及提交凭据验证能力。
     */
    private final ReviewService reviews;

    /**
     * 与业务变化一致的审计写入和历史读取能力。
     */
    private final AuditService audits;

    /**
     * 已配置的成熟密码散列实现，不自行比较明文或截断密码。
     */
    private final PasswordEncoder encoder;

    /**
     * 统一的明确类型 JSON 编解码适配。
     */
    private final JsonCodec json;

    /**
     * 在当前权限和查询范围内过滤、统计并返回有效分页记录。
     */
    @Transactional(readOnly = true)
    public PageVO<UserRowVO> list(UserQuery query) {
        guard.shared();
        permissions.require("users.view");
        List<Long> branch = query.getDepartmentId() == null
                ? null
                : views.branch(Ids.parse(query.getDepartmentId()), query.getIncludeDescendants());
        Page<UserPO> page = query.toMpPage("u.updated_at", false);
        page.addOrder(OrderItem.desc("u.id"));
        users.page(page, query, branch, null);
        if (page.getTotal() == 0) {
            page.setCurrent(1);
        } else if (page.getCurrent() > page.getPages()) {
            page.setCurrent(page.getPages());
            page.setSearchCount(false);
            users.page(page, query, branch, null);
        }
        return PageVO.from(page, views::userRow);
    }

    /**
     * 按当前查看资格读取完整业务详情或真实历史快照。
     */
    @Transactional(readOnly = true)
    public UserDetailVO detail(String id) {
        guard.shared();
        permissions.require("users.view");
        return views.userDetail(views.user(Ids.parse(id)));
    }

    /**
     * 校验创建提案并生成绑定操作者和完整内容的核对凭据。
     */
    @Transactional(readOnly = true)
    public ImpactPreviewVO previewCreate(CreateUserPreviewDTO data) {
        guard.shared();
        permissions.require("users.create");
        validateCreation(data.getProposal());
        return reviews.preview(
                "CREATE",
                null,
                null,
                data.getProposal(),
                List.of(
                        change(
                                "username",
                                "登录名",
                                null,
                                data.getProposal().getUsername().trim()),
                        change(
                                "displayName",
                                "姓名",
                                null,
                                data.getProposal().getDisplayName().trim()),
                        change(
                                "department",
                                "主部门",
                                null,
                                views.departmentRef(Ids.parse(data.getProposal()
                                                .getMembership()
                                                .getPrimaryDepartmentId()))
                                        .getName()),
                        change(
                                "roles",
                                "个人角色",
                                null,
                                roleNames(data.getProposal().getRoles())),
                        change("password", "首次登录", null, "需修改初始密码后重新登录")),
                List.of(),
                List.of());
    }

    /**
     * 在核对当前提案后原子建立人员、凭据、组织及获准角色。
     */
    public MutationVO create(CreateUserDTO data, String command) {
        return mutations.execute("users.create", command, "create", data, null, true, false, () -> {
            NewUserProfileDTO profile = BeanUtil.copyProperties(data, NewUserProfileDTO.class);
            validateCreation(profile);
            PasswordPolicy.requireStrong(data.getInitialPassword());
            reviews.verify("CREATE", null, null, data, data.getReviewToken());
            profile.setUsername(profile.getUsername().trim());
            profile.setDisplayName(profile.getDisplayName().trim());
            users.insert(profile, UserContext.getUser());
            long id = guard.lastId();
            users.insertCredential(id, encoder.encode(data.getInitialPassword()));
            assignments.applyMembership(id, data.getMembership());
            assignments.applyRoles(id, data.getRoles());
            audit(
                    "users.create",
                    id,
                    null,
                    List.of(
                            change(
                                    "identity",
                                    "人员",
                                    null,
                                    profile.getDisplayName() + "（" + profile.getUsername() + "）"),
                            change("roles", "个人角色", null, roleNames(data.getRoles()))),
                    List.of(),
                    views.roleVersions(id),
                    command,
                    true);
            return result(id, command);
        });
    }

    /**
     * 在新增写入前核验登录名唯一、主兼任及初始角色的完整资格。
     */
    private void validateCreation(NewUserProfileDTO data) {
        assignments.validateMembership(0, data.getMembership(), true);
        if (!data.getRoles().isEmpty()) {
            permissions.require("users.assign");
        }
        assignments.validateRoles(0, data.getRoles(), "users.assign", false);
        if (users.byUsername(data.getUsername().trim()) != null) {
            throw new BizIllegalException(ErrorCode.IAM_LOGIN_NAME_EXISTS, "登录名已存在，请更换");
        }
    }

    /**
     * 核对当前动作的完整提案、影响与版本，不在预览阶段写入业务事实。
     */
    @Transactional(readOnly = true)
    public ImpactPreviewVO preview(String textId, UserImpactPreviewDTO data) {
        guard.shared();
        String operation =
                switch (data.getAction()) {
                    case "UPDATE" -> "users.edit";
                    case "TRANSFER" -> "users.transfer";
                    case "DISABLE" -> "users.disable";
                    case "ENABLE" -> "users.enable";
                    case "ARCHIVE" -> "users.archive";
                    case "RESET_PASSWORD" -> "users.password";
                    case "PROTECTED_ROLES" -> "roles.protected";
                    default -> throw BizIllegalException.invalid("未知人员操作");
                };
        permissions.require(operation);
        long id = Ids.parse(textId);
        UserPO user = checked(id, data.getExpectedVersion());
        Object proposal;
        List<ChangeVO> changes = new ArrayList<>();
        switch (data.getAction()) {
            case "UPDATE" -> {
                var p = ((UserUpdatePreviewDTO) data).getProposal();
                proposal = p;
                validateMembershipEdit(id, p.getMembership());
                changes.add(change(
                        "displayName",
                        "姓名",
                        user.getDisplayName(),
                        p.getDisplayName().trim()));
                changes.add(change(
                        "department",
                        "主部门",
                        primaryName(user),
                        views.departmentRef(Ids.parse(p.getMembership().getPrimaryDepartmentId()))
                                .getName()));
            }
            case "TRANSFER" -> {
                var p = ((UserTransferPreviewDTO) data).getProposal();
                proposal = p;
                assignments.validateMembership(id, p.getMembership(), false);
                changes.add(change(
                        "department",
                        "主部门",
                        primaryName(user),
                        views.departmentRef(Ids.parse(p.getMembership().getPrimaryDepartmentId()))
                                .getName()));
                changes.add(change(
                        "secondaryDepartments",
                        "兼任部门",
                        secondaryNames(user),
                        departmentNames(p.getMembership().getSecondaryDepartmentIds())));
            }
            case "ENABLE" -> {
                var p = ((UserEnablePreviewDTO) data).getProposal();
                proposal = p;
                requireState(user, "DISABLED");
                requireProtectedTarget(user);
                if (!Boolean.TRUE.equals(p.getReviewedAccess())) {
                    throw BizIllegalException.invalid("请完成恢复前复核");
                }
                assignments.validateMembership(id, p.getMembership(), true);
                assignments.validateRoles(id, p.getRoles(), "users.enable", false);
                changes.add(change("lifecycle", "账号状态", "停用", "恢复使用，旧会话和旧任务不恢复"));
                changes.add(change("roles", "复核角色", roleNamesFromUser(user), roleNames(p.getRoles())));
            }
            case "DISABLE" -> {
                proposal = ((UserDisablePreviewDTO) data).getProposal();
                if (user.getLifecycle().equals("DISABLED")) {
                    throw BizIllegalException.state("账号已停用");
                }
                requireProtectedTarget(user);
                changes.add(change("lifecycle", "账号状态", views.status(user), "停用并停止访问"));
            }
            case "ARCHIVE" -> {
                proposal = ((UserArchivePreviewDTO) data).getProposal();
                requireState(user, "DISABLED");
                requireProtectedTarget(user);
                checkArchive(id);
                changes.add(change("lifecycle", "账号状态", "停用", "归档，只读保留历史"));
                changes.add(change(
                        "assignments",
                        "当前角色与部门归属",
                        roleNamesFromUser(user) + "；" + primaryName(user),
                        "结束当前关系，保留归档快照"));
            }
            case "RESET_PASSWORD" -> {
                proposal = ((UserResetPasswordPreviewDTO) data).getProposal();
                requireProtectedTarget(user);
                changes.add(change("credential", "登录密码", "不回显", "设置新初始密码，下次登录先改密"));
            }
            case "PROTECTED_ROLES" -> {
                var p = ((UserProtectedRolesPreviewDTO) data).getProposal();
                proposal = p;
                permissions.require("users.assign");
                assignments.validateRoles(id, p.getRoles(), "users.assign", true);
                changes.add(change("roles", "受保护角色分配", roleNamesFromUser(user), roleNames(p.getRoles())));
            }
            default -> throw BizIllegalException.invalid("未知人员操作");
        }
        changes.add(
                0,
                change(
                        "identity",
                        "目标人员",
                        user.getDisplayName() + "（" + user.getUsername() + "）",
                        user.getDisplayName() + "（" + user.getUsername() + "）"));
        return reviews.preview(
                data.getAction(),
                textId,
                data.getExpectedVersion(),
                proposal,
                changes,
                List.of(new ImpactCountVO("USER", 1)),
                List.of());
    }

    /**
     * 完整保存获准的人员资料及归属变化，任一失败整份表单回滚。
     */
    public MutationVO update(String textId, UpdateUserDTO data, String command) {
        long id = Ids.parse(textId);
        boolean authChange = !assignments.membershipMatches(id, data.getMembership());
        return mutations.execute(
                "users.edit", command, textId, data, data.getExpectedVersion(), authChange, false, () -> {
                    UserPO user = checked(id, data.getExpectedVersion());
                    validateMembershipEdit(id, data.getMembership());
                    reviews.verify("UPDATE", textId, data.getExpectedVersion(), data, data.getReviewToken());
                    boolean same = Objects.equals(
                                    user.getDisplayName(), data.getDisplayName().trim())
                            && Objects.equals(user.getPhone(), blank(data.getPhone()))
                            && Objects.equals(user.getEmail(), blank(data.getEmail()))
                            && Objects.equals(user.getManagementNote(), blank(data.getManagementNote()))
                            && assignments.membershipMatches(id, data.getMembership());
                    if (same) {
                        return result(id, command);
                    }
                    data.setDisplayName(data.getDisplayName().trim());
                    data.setPhone(blank(data.getPhone()));
                    data.setEmail(blank(data.getEmail()));
                    data.setManagementNote(blank(data.getManagementNote()));
                    var before = views.roleVersions(id);
                    String oldDepartment = primaryName(user);
                    if (users.profile(id, data) != 1) {
                        throw BizIllegalException.conflict();
                    }
                    assignments.applyMembership(id, data.getMembership());
                    audit(
                            "users.edit",
                            id,
                            null,
                            List.of(
                                    change("displayName", "姓名", user.getDisplayName(), data.getDisplayName()),
                                    change("department", "主部门", oldDepartment, primaryName(views.user(id)))),
                            before,
                            before,
                            command,
                            authChange);
                    return result(id, command);
                });
    }

    /**
     * 资料表单涉及归属变化时补充核验调整归属能力。
     */
    private void validateMembershipEdit(long id, MembershipSelectionDTO membership) {
        if (!assignments.membershipMatches(id, membership)) {
            permissions.require("users.transfer");
        }
        assignments.validateMembership(id, membership, false);
    }

    /**
     * 完整调整人员主兼任归属，不同时更改个人角色。
     */
    public MutationVO transfer(String textId, TransferUserDTO data, String command) {
        long id = Ids.parse(textId);
        return mutations.execute(
                "users.transfer", command, textId, data, data.getExpectedVersion(), true, false, () -> {
                    UserPO user = checked(id, data.getExpectedVersion());
                    assignments.validateMembership(id, data.getMembership(), false);
                    reviews.verify("TRANSFER", textId, data.getExpectedVersion(), data, data.getReviewToken());
                    if (assignments.membershipMatches(id, data.getMembership())) {
                        return result(id, command);
                    }
                    String before = primaryName(user) + "；" + secondaryNames(user);
                    assignments.applyMembership(id, data.getMembership());
                    users.touch(id);
                    audit(
                            "users.transfer",
                            id,
                            data.getReason(),
                            List.of(change(
                                    "memberships",
                                    "部门归属",
                                    before,
                                    primaryName(views.user(id)) + "；" + secondaryNames(views.user(id)))),
                            views.roleVersions(id),
                            views.roleVersions(id),
                            command,
                            true);
                    return result(id, command);
                });
    }

    /**
     * 按当前版本和完整能力直接保存个人角色分配。
     */
    public MutationVO assignRoles(String textId, RoleAssignmentDTO data, String command) {
        long id = Ids.parse(textId);
        return mutations.execute("users.assign", command, textId, data, data.getExpectedVersion(), true, false, () -> {
            UserPO user = checked(id, data.getExpectedVersion());
            assignments.validateRoles(id, data.getRoles(), "users.assign", false);
            if (assignments.rolesMatch(id, data.getRoles())) {
                return result(id, command);
            }
            var before = views.roleVersions(id);
            String names = roleNamesFromUser(user);
            assignments.applyRoles(id, data.getRoles());
            users.touch(id);
            audit(
                    "users.assign",
                    id,
                    null,
                    List.of(change("roles", "个人角色", names, roleNames(data.getRoles()))),
                    before,
                    views.roleVersions(id),
                    command,
                    true);
            return result(id, command);
        });
    }

    /**
     * 按核对凭据和当前版本停用账号，并可靠通知旧认证失效。
     */
    public MutationVO disable(String textId, VersionActionDTO data, String command) {
        long id = Ids.parse(textId);
        return mutations.execute("users.disable", command, textId, data, data.getExpectedVersion(), true, false, () -> {
            UserPO user = checked(id, data.getExpectedVersion());
            requireProtectedTarget(user);
            if (user.getLifecycle().equals("DISABLED")) {
                throw BizIllegalException.state("账号已停用");
            }
            reviews.verify("DISABLE", textId, data.getExpectedVersion(), data, data.getReviewToken());
            users.lifecycle(id, "DISABLED", data.getReason(), null);
            mutations.invalidateAuthentication(id, "USER_DISABLED");
            audit(
                    "users.disable",
                    id,
                    data.getReason(),
                    List.of(change("lifecycle", "账号状态", views.status(user), "DISABLED")),
                    views.roleVersions(id),
                    views.roleVersions(id),
                    command,
                    true);
            return result(id, command);
        });
    }

    /**
     * 复核当前归属、角色及来源后恢复账号，不复活旧会话和任务。
     */
    public MutationVO enable(String textId, EnableUserDTO data, String command) {
        long id = Ids.parse(textId);
        return mutations.execute("users.enable", command, textId, data, data.getExpectedVersion(), true, false, () -> {
            UserPO user = checked(id, data.getExpectedVersion());
            requireState(user, "DISABLED");
            requireProtectedTarget(user);
            if (!Boolean.TRUE.equals(data.getReviewedAccess())) {
                throw BizIllegalException.invalid("请完成恢复前复核");
            }
            assignments.validateMembership(id, data.getMembership(), true);
            assignments.validateRoles(id, data.getRoles(), "users.enable", false);
            if (!assignments.membershipMatches(id, data.getMembership())) {
                permissions.require("users.transfer");
            }
            if (!assignments.rolesMatch(id, data.getRoles())) {
                permissions.require("users.assign");
                assignments.validateRoles(id, data.getRoles(), "users.assign", false);
            }
            reviews.verify("ENABLE", textId, data.getExpectedVersion(), data, data.getReviewToken());
            var before = views.roleVersions(id);
            assignments.applyMembership(id, data.getMembership());
            assignments.applyRoles(id, data.getRoles());
            users.lifecycle(id, "ENABLED", data.getReason(), null);
            mutations.invalidateAuthentication(id, "USER_ENABLED");
            audit(
                    "users.enable",
                    id,
                    data.getReason(),
                    List.of(change("lifecycle", "账号状态", "DISABLED", "ENABLED")),
                    before,
                    views.roleVersions(id),
                    command,
                    true);
            return result(id, command);
        });
    }

    /**
     * 核对生命周期及未处理关联后归档人员并保存历史归属。
     */
    public MutationVO archive(String textId, VersionActionDTO data, String command) {
        long id = Ids.parse(textId);
        return mutations.execute("users.archive", command, textId, data, data.getExpectedVersion(), true, false, () -> {
            UserPO user = checked(id, data.getExpectedVersion());
            requireState(user, "DISABLED");
            requireProtectedTarget(user);
            checkArchive(id);
            reviews.verify("ARCHIVE", textId, data.getExpectedVersion(), data, data.getReviewToken());
            var before = views.roleVersions(id);
            String affiliation = json.write(views.affiliation(user));
            memberships.forUser(id).stream()
                    .map(row -> row.getDepartmentId())
                    .distinct()
                    .sorted()
                    .forEach(departments::touch);
            memberships.endUser(id, UserContext.getUser());
            roles.endUser(id, UserContext.getUser());
            users.lifecycle(id, "ARCHIVED", data.getReason(), affiliation);
            mutations.invalidateAuthentication(id, "USER_ARCHIVED");
            audit(
                    "users.archive",
                    id,
                    data.getReason(),
                    List.of(
                            change("lifecycle", "账号状态", "DISABLED", "ARCHIVED"),
                            change("assignments", "当前角色与成员关系", "保留中", "已结束并保存归档快照")),
                    before,
                    List.of(),
                    command,
                    true);
            return result(id, command);
        });
    }

    /**
     * 检查人员归档前必须先处理的外部当前关联。
     */
    private void checkArchive(long id) {
        if (departments.managedBy(id) > 0) {
            throw new BizIllegalException(ErrorCode.REFERENCE_IN_USE, "该人员仍是部门负责人，请先处理关联");
        }
    }

    /**
     * 管理员重置目标初始凭据并保留恢复途径检查及全会话失效。
     */
    public MutationVO resetPassword(String textId, ResetPasswordDTO data, String command) {
        long id = Ids.parse(textId);
        PasswordPolicy.requireStrong(data.getInitialPassword());
        String encoded = encoder.encode(data.getInitialPassword());
        return mutations.execute(
                "users.password", command, textId, data, data.getExpectedVersion(), true, false, () -> {
                    UserPO user = checked(id, data.getExpectedVersion());
                    requireProtectedTarget(user);
                    reviews.verify("RESET_PASSWORD", textId, data.getExpectedVersion(), data, data.getReviewToken());
                    users.password(id, encoded);
                    users.invalidate(id, true);
                    mutations.invalidateAuthentication(id, "PASSWORD_RESET");
                    audit(
                            "users.password",
                            id,
                            data.getReason(),
                            List.of(change("credential", "登录凭据", "不回显", "已重置，下次登录需改密")),
                            views.roleVersions(id),
                            views.roleVersions(id),
                            command,
                            true);
                    return result(id, command);
                });
    }

    /**
     * 读取当前登录本人的资料和组织角色，不要求管理他人的权限。
     */
    @Transactional(readOnly = true)
    public AccountVO account() {
        guard.shared();
        return views.account(views.user(UserContext.getUser()));
    }

    /**
     * 以当前本人身份和版本原子保存联系方式，允许显式清空。
     */
    public AccountVO contact(UpdateContactDTO data, String command) {
        long id = UserContext.getUser();
        mutations.execute(
                "account.contact", command, Long.toString(id), data, data.getExpectedVersion(), false, false, () -> {
                    UserPO user = checked(id, data.getExpectedVersion());
                    String phone = blank(data.getPhone()), email = blank(data.getEmail());
                    if (Objects.equals(user.getPhone(), phone) && Objects.equals(user.getEmail(), email)) {
                        return result(id, command);
                    }
                    data.setPhone(phone);
                    data.setEmail(email);
                    if (users.contact(id, data) != 1) {
                        throw BizIllegalException.conflict();
                    }
                    audit(
                            "account.contact",
                            id,
                            null,
                            List.of(
                                    change("phone", "手机号", user.getPhone(), phone),
                                    change("email", "邮箱", user.getEmail(), email)),
                            views.roleVersions(id),
                            views.roleVersions(id),
                            command,
                            false);
                    return result(id, command);
                });
        return account();
    }

    /**
     * 按具体动作核验并返回可选部门及原已选只读项。
     */
    @Transactional(readOnly = true)
    public List<DepartmentOptionVO> departmentOptions(DepartmentOptionQuery query) {
        guard.shared();
        switch (String.valueOf(query.getPurpose())) {
            case "USER_CREATE" -> permissions.require("users.create");
            case "USER_TRANSFER" -> permissions.require("users.transfer");
            case "USER_ENABLE" -> permissions.require("users.enable");
            case "DEPARTMENT_EDIT" -> permissions.requireAny("departments.create", "departments.edit");
            case "MEMBER_TRANSFER" -> permissions.require("departments.transfer-members");
            default -> throw BizIllegalException.invalid("部门选项用途不正确");
        }
        Set<String> selected = new HashSet<>(query.getSelectedIds());
        var result = departments.all().stream()
                .filter(item -> item.getLifecycle().equals("ENABLED") || selected.contains(Ids.text(item.getId())))
                .filter(item -> IamViewAssembler.matches(item.getName() + " " + item.getCode(), query.getQ()))
                .map(item -> new DepartmentOptionVO(
                        Ids.text(item.getId()),
                        item.getCode(),
                        item.getName(),
                        item.getLifecycle(),
                        item.getVersion(),
                        item.getLifecycle().equals("ENABLED")))
                .toList();
        optionLimit(result.size());
        return result;
    }

    /**
     * 按用途、当前状态、完整能力及授予上限生成可选择或只读保留角色。
     */
    @Transactional(readOnly = true)
    public List<RoleOptionVO> roleOptions(RoleOptionQuery query) {
        guard.shared();
        String operation =
                switch (String.valueOf(query.getPurpose())) {
                    case "USER_CREATE" -> "users.create";
                    case "USER_ASSIGN" -> "users.assign";
                    case "USER_ENABLE" -> "users.enable";
                    default -> throw BizIllegalException.invalid("角色选项用途不正确");
                };
        permissions.require(operation);
        if (query.getPurpose().equals("USER_CREATE")) {
            operation = "users.assign";
        }
        Set<String> upper = permissions.grantable(UserContext.getUser(), operation);
        Set<Long> selected = query.getUserId() == null
                ? Set.of()
                : new HashSet<>(roles.assignments(Ids.parse(query.getUserId())).stream()
                        .map(row -> row.getRoleId())
                        .toList());
        var result = roles.all().stream()
                .filter(item -> item.getLifecycle().equals("ENABLED") || selected.contains(item.getId()))
                .filter(item -> IamViewAssembler.matches(item.getName(), query.getQ()))
                .map(item -> {
                    long id = item.getId();
                    boolean protectedRole = permissions.protectedRole(id);
                    boolean selectable = item.getLifecycle().equals("ENABLED")
                            && !protectedRole
                            && upper.containsAll(roles.permissions(id))
                            && upper.containsAll(roles.grantable(id));
                    return new RoleOptionVO(
                            Ids.text(id),
                            item.getCode(),
                            item.getName(),
                            item.getLifecycle(),
                            item.getVersion(),
                            protectedRole,
                            selectable,
                            selected.contains(id));
                })
                .toList();
        optionLimit(result.size());
        return result;
    }

    /**
     * 按部门负责人或加入成员的具体动作资格返回可选人员。
     */
    @Transactional(readOnly = true)
    public List<UserOptionVO> userOptions(UserOptionQuery query) {
        guard.shared();
        Set<Long> existing = new HashSet<>();
        switch (String.valueOf(query.getPurpose())) {
            case "DEPARTMENT_MANAGER" -> permissions.requireAny("departments.create", "departments.edit");
            case "MEMBER_ADD" -> {
                permissions.require("departments.add-member");
                long department = Ids.parse(query.getDepartmentId());
                views.department(department);
                memberships.forDepartment(department).forEach(row -> existing.add(row.getUserId()));
            }
            default -> throw BizIllegalException.invalid("人员选项用途不正确");
        }
        var result = users.all().stream()
                .filter(item -> !item.getLifecycle().equals("ARCHIVED") && !existing.contains(item.getId()))
                .filter(item -> !query.getPurpose().equals("MEMBER_ADD")
                        || item.getLifecycle().equals("ENABLED"))
                .filter(item ->
                        IamViewAssembler.matches(item.getDisplayName() + " " + item.getUsername(), query.getQ()))
                .map(item -> new UserOptionVO(
                        Ids.text(item.getId()),
                        item.getUsername(),
                        item.getDisplayName(),
                        views.status(item),
                        item.getVersion(),
                        true))
                .toList();
        optionLimit(result.size());
        return result;
    }

    /**
     * 选项超过可披露上限时要求缩小搜索，不能静默截断。
     */
    private void optionLimit(int size) {
        if (size > 100) {
            throw new BizIllegalException(ErrorCode.CAPACITY_EXCEEDED, "选项较多，请输入名称缩小搜索范围");
        }
    }

    /**
     * 拒绝过期修订和归档对象写入，返回当前权威记录。
     */
    private UserPO checked(long id, Integer expected) {
        var user = views.user(id);
        if (user.getVersion() != expected) {
            throw BizIllegalException.conflict();
        }
        if (user.getLifecycle().equals("ARCHIVED")) {
            throw BizIllegalException.state("归档人员仅供回看");
        }
        return user;
    }

    /**
     * 核对当前生命周期允许执行本次动作。
     */
    private void requireState(UserPO data, String lifecycle) {
        if (!data.getLifecycle().equals(lifecycle)) {
            throw BizIllegalException.state("当前账号状态不允许此操作");
        }
    }

    /**
     * 目标人员保留受保护能力时要求受保护管理资格。
     */
    private void requireProtectedTarget(UserPO data) {
        if (permissions.protectedUser(data.getId())) {
            permissions.require("roles.protected");
        }
    }

    /**
     * 从当前或归档归属读取主部门名称用于核对展示。
     */
    private String primaryName(UserPO user) {
        var primary = views.affiliation(user).getPrimaryDepartment();
        return primary == null ? null : primary.getName();
    }

    /**
     * 合并人员当前或归档兼任名称，保留主兼任含义。
     */
    private String secondaryNames(UserPO user) {
        return String.join(
                "、",
                views.affiliation(user).getSecondaryDepartments().stream()
                        .map(DepartmentRefVO::getName)
                        .toList());
    }

    /**
     * 从稳定部门标识读取当前名称用于可读核对。
     */
    private String departmentNames(List<String> ids) {
        return String.join(
                "、",
                ids.stream()
                        .map(id -> views.departmentRef(Ids.parse(id)).getName())
                        .toList());
    }

    /**
     * 从当前稳定角色引用读取名称用于完整核对。
     */
    private String roleNames(List<RoleVersionRefVO> selected) {
        return String.join(
                "、",
                selected.stream()
                        .map(ref -> views.role(Ids.parse(ref.getRoleId())).getName())
                        .toList());
    }

    /**
     * 读取人员当前或归档快照中的角色显示名称。
     */
    private String roleNamesFromUser(UserPO user) {
        return String.join(
                "、",
                views.affiliation(user).getRoles().stream()
                        .map(RoleRefVO::getName)
                        .toList());
    }

    /**
     * 仅返回本次提交的安全目标标识和当前修订。
     */
    private MutationVO result(long id, String command) {
        return new MutationVO(command, "user", Long.toString(id), views.user(id).getVersion());
    }

    /**
     * 将可空普通资料归一，凭据字段不经过此方法。
     */
    private static String blank(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /**
     * 生成一项不包含秘密值的业务差异。
     */
    private static ChangeVO change(String field, String label, String before, String after) {
        return new ChangeVO(field, label, before, after);
    }

    /**
     * 为本次真实变化写入必要差异、历史角色和提交关联。
     */
    private void audit(
            String operation,
            long id,
            String reason,
            List<ChangeVO> changes,
            List<RoleVersionRefVO> before,
            List<RoleVersionRefVO> after,
            String command,
            boolean authChange) {
        audits.record(new AuditWriteDTO(
                operation,
                "user",
                Long.toString(id),
                views.user(id).getDisplayName(),
                reason,
                changes,
                before,
                after,
                mutations.auditSecurityVersion(authChange),
                command));
    }
}
