package com.dataocean.module.iam.service.impl;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.TextSearch;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.iam.entity.dto.CreatePreviewDTO;
import com.dataocean.module.iam.entity.dto.CreateRoleDTO;
import com.dataocean.module.iam.entity.dto.ProtectedRoleAssignmentDTO;
import com.dataocean.module.iam.entity.dto.RecoverUserDTO;
import com.dataocean.module.iam.entity.dto.RecoveryPreviewDTO;
import com.dataocean.module.iam.entity.dto.RoleGrantLimitProposalDTO;
import com.dataocean.module.iam.entity.dto.RoleImpactPreviewDTO;
import com.dataocean.module.iam.entity.dto.RoleProfileDTO;
import com.dataocean.module.iam.entity.dto.RoleProposalDTO;
import com.dataocean.module.iam.entity.dto.RoleProtectedPermissionsProposalDTO;
import com.dataocean.module.iam.entity.dto.UpdateGrantLimitDTO;
import com.dataocean.module.iam.entity.dto.UpdateProtectedRoleDTO;
import com.dataocean.module.iam.entity.dto.UpdateRoleDTO;
import com.dataocean.module.iam.entity.dto.VersionActionDTO;
import com.dataocean.module.iam.entity.po.RolePO;
import com.dataocean.module.iam.entity.po.UserPO;
import com.dataocean.module.iam.entity.query.PersonQuery;
import com.dataocean.module.iam.entity.query.RoleQuery;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.GrantLimitVO;
import com.dataocean.module.iam.entity.vo.ImpactCountVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.RecoveryPreviewVO;
import com.dataocean.module.iam.entity.vo.RoleDetailVO;
import com.dataocean.module.iam.entity.vo.RoleRowVO;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;
import com.dataocean.module.iam.entity.vo.UserRowVO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.PermissionMapper;
import com.dataocean.module.iam.mapper.RoleAssignmentMapper;
import com.dataocean.module.iam.mapper.RoleMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.security.IamRequestPermission;
import com.dataocean.module.iam.security.RoleProfilePermission;
import com.dataocean.module.iam.service.AssignmentService;
import com.dataocean.module.iam.service.IamViewAssembler;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ReviewService;
import com.dataocean.module.iam.service.RoleService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import cn.hutool.core.bean.BeanUtil;
import lombok.RequiredArgsConstructor;

/**
 * 以角色真实能力和当前授予上限决策，不信任名称或内置标记。
 */
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    /**
     * 当前角色、分配关系及不可变版本快照。
     */
    private final RoleMapper roles;

    /**
     * 查询当前角色分配关系。
     */
    private final RoleAssignmentMapper roleAssignments;

    /**
     * 成员列表和人员聚合版本。
     */
    private final UserMapper users;

    /**
     * 组织和管理能力变更的事务锁。
     */
    private final GuardMapper guard;

    /**
     * 角色可选能力的系统目录。
     */
    private final PermissionMapper directory;

    /**
     * 当前操作者的使用及授予资格。
     */
    private final PermissionService permissions;

    /**
     * 预览和写入使用同一套角色操作与完整提案授权规则。
     */
    private final IamRequestPermission requestPermission;

    /**
     * 对受保护内容和当前授予上限做角色提案授权。
     */
    private final RoleProfilePermission profilePermission;

    /**
     * 当前事实的安全视图组装。
     */
    private final IamViewAssembler views;

    /**
     * 当前角色分配的共用校验和写入。
     */
    private final AssignmentService assignments;

    /**
     * 原子命令、恢复图和可靠通知。
     */
    private final MutationService mutations;

    /**
     * 绑定操作者、版本和完整提案的核对凭据。
     */
    private final ReviewService reviews;

    /**
     * 与业务同时提交的历史依据。
     */
    private final AuditService audits;

    /**
     * 明确快照结构的序列化。
     */
    private final JsonCodec json;

    /**
     * 在同一读取边界内统计并分页返回角色。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<RoleRowVO> list(RoleQuery query) {
        guard.shared();
        permissions.require("roles.view");
        Page<RolePO> page = query.toMpPage("updated_at", false);
        page.addOrder(OrderItem.desc("id"));
        return views.roleRows(roles.page(page, query));
    }

    /**
     * 角色详情仅由独立页面权放行。
     */
    @Override
    @Transactional(readOnly = true)
    public RoleDetailVO detail(String id) {
        guard.shared();
        permissions.require("roles.view");
        return views.roleDetail(views.role(Ids.parse(id)));
    }

    /**
     * 当前分配按人员分页，停用人员不会被误作已移出。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<UserRowVO> members(String id, PersonQuery query) {
        guard.shared();
        permissions.require("roles.view");
        views.role(Ids.parse(id));
        Page<UserPO> page = query.toMpPage("u.updated_at", false);
        page.addOrder(OrderItem.desc("u.id"));
        return views.userRows(users.page(page, query, null, Ids.parse(id)));
    }

    /**
     * 新角色核对不带受保护能力或隐式上限。
     */
    @Override
    @Transactional(readOnly = true)
    public ImpactPreviewVO previewCreate(CreatePreviewDTO<RoleProfileDTO> data) {
        guard.shared();
        permissions.require("roles.create");
        validateProfile(null, data.getProposal(), "roles.create", false);
        return reviews.preview(
                "CREATE",
                null,
                null,
                data.getProposal(),
                profileChanges(null, data.getProposal()),
                List.of(),
                List.of());
    }

    /**
     * 新建与首版快照、审计及 Outbox 原子提交。
     */
    @Override
    public MutationVO create(CreateRoleDTO data, String command) {
        return mutations.execute("roles.create", command, "create", data, null, true, false, () -> {
            // 预览与正式请求共用业务结构和验证规则。
            RoleProfileDTO profile = BeanUtil.copyProperties(data, RoleProfileDTO.class);
            validateProfile(null, profile, "roles.create", false);
            reviews.verify("CREATE", null, null, data, data.getReviewToken());
            profile.setName(profile.getName().trim());
            long id = roles.create(
                    profile,
                    "role-" + UUID.randomUUID(),
                    TextSearch.key(profile.getName()),
                    false,
                    UserContext.getUser());
            profile.getPermissionCodes().forEach(code -> roles.addPermission(id, code, UserContext.getUser()));
            snapshot(id);
            audit(id, "roles.create", null, profileChanges(null, profile), command);
            return result(id, command);
        });
    }

    /**
     * 根据明确动作类型核对完整提案及所有当前成员数量。
     */
    @Override
    @Transactional(readOnly = true)
    public ImpactPreviewVO preview(String textId, RoleImpactPreviewDTO data) {
        guard.shared();
        requestPermission.requireRolePreview(data.getAction());
        long id = Ids.parse(textId);
        RolePO role = checked(id, data.getExpectedVersion());

        // 请求绑定时已按动作选定完整角色提案，业务分支继续单独核权。
        RoleProposalDTO proposal = data.getProposal();

        List<ChangeVO> changes;
        switch (data.getAction()) {
            case "UPDATE" -> {
                // 普通编辑保留已有受保护使用项。
                var value = (RoleProfileDTO) proposal;
                validateProfile(role, value, RoleProfilePermission.EDIT, false);
                changes = profileChanges(role, value);
            }
            case "GRANT_LIMIT" -> {
                // 上限变化不回写他人的既有使用权。
                var value = (RoleGrantLimitProposalDTO) proposal;
                validateGrants(role, value.getGrantablePermissionCodes());
                changes = List.of(change(
                        "grantablePermissionCodes",
                        "可授予操作",
                        permissionNames(roles.grantable(id)),
                        permissionNames(value.getGrantablePermissionCodes())));
            }
            case "PROTECTED_PERMISSIONS" -> {
                // 受保护修改只能使用当前已有资格进行授权。
                var value = (RoleProtectedPermissionsProposalDTO) proposal;
                validateProfile(role, value, "roles.protected", true);
                changes = profileChanges(role, value);
            }
            case "DISABLE", "ENABLE", "ARCHIVE" -> {
                // 状态动作也须核对角色实际受保护内容。
                validateLifecycle(role, data.getAction());
                changes = List.of(change("lifecycle", "角色状态", role.getLifecycle(), targetLifecycle(data.getAction())));
            }
            default -> throw BizIllegalException.invalid("未知角色操作");
        }
        return reviews.preview(
                data.getAction(),
                textId,
                role.getVersion(),
                proposal,
                changes,
                List.of(new ImpactCountVO("USER", roleAssignments.memberCount(id))),
                List.of());
    }

    /**
     * 普通编辑不创建额外空修订，变更时追加真实角色快照。
     */
    @Override
    public MutationVO update(String textId, UpdateRoleDTO data, String command) {
        long id = Ids.parse(textId);
        return mutations.execute(
                RoleProfilePermission.EDIT, command, textId, data, data.getExpectedVersion(), true, false, () -> {
                    // 完整能力与核对凭据都在配置锁后重新检查。
                    RolePO role = checked(id, data.getExpectedVersion());
                    RoleProfileDTO profile = BeanUtil.copyProperties(data, RoleProfileDTO.class);
                    validateProfile(role, profile, RoleProfilePermission.EDIT, false);
                    reviews.verify("UPDATE", textId, role.getVersion(), data, data.getReviewToken());
                    return saveProfile(role, profile, RoleProfilePermission.EDIT, null, command);
                });
    }

    /**
     * 状态变更保留成员关系，由事务结束时检查最后恢复途径。
     */
    @Override
    public MutationVO lifecycle(String textId, String action, VersionActionDTO data, String command) {
        long id = Ids.parse(textId);
        String operation = "roles." + action.toLowerCase(Locale.ROOT);
        return mutations.execute(operation, command, textId, data, data.getExpectedVersion(), true, false, () -> {
            // 归档、受保护项及启用上限均使用当前角色事实。
            RolePO role = checked(id, data.getExpectedVersion());
            validateLifecycle(role, action);
            reviews.verify(action, textId, role.getVersion(), data, data.getReviewToken());
            roles.lifecycle(id, targetLifecycle(action), data.getReason());
            snapshot(id);
            audit(
                    id,
                    operation,
                    data.getReason(),
                    List.of(change("lifecycle", "角色状态", role.getLifecycle(), targetLifecycle(action))),
                    command);
            return result(id, command);
        });
    }

    /**
     * 授予上限独立核权，不随普通详情披露。
     */
    @Override
    @Transactional(readOnly = true)
    public GrantLimitVO grantLimit(String textId) {
        guard.shared();
        permissions.require("roles.protected");
        RolePO role = views.role(Ids.parse(textId));
        return new GrantLimitVO(textId, role.getVersion(), roles.grantable(role.getId()));
    }

    /**
     * 修改上限只限制后续授予，禁止提高操作者自身上限。
     */
    @Override
    public MutationVO updateGrantLimit(String textId, UpdateGrantLimitDTO data, String command) {
        long id = Ids.parse(textId);
        return mutations.execute(
                "roles.protected", command, textId, data, data.getExpectedVersion(), true, false, () -> {
                    // 当前资格先于写入检查，提案不能为自身提供授权。
                    RolePO role = checked(id, data.getExpectedVersion());
                    validateGrants(role, data.getGrantablePermissionCodes());
                    reviews.verify("GRANT_LIMIT", textId, role.getVersion(), data, data.getReviewToken());
                    var before = roles.grantable(id);
                    if (new HashSet<>(before).equals(new HashSet<>(data.getGrantablePermissionCodes()))) {
                        return result(id, command);
                    }
                    roles.clearGrants(id);
                    data.getGrantablePermissionCodes().forEach(code -> roles.addGrant(id, code, UserContext.getUser()));
                    roles.touch(id);
                    snapshot(id);
                    audit(
                            id,
                            "roles.protected",
                            data.getReason(),
                            List.of(change(
                                    "grantablePermissionCodes",
                                    "可授予操作",
                                    permissionNames(before),
                                    permissionNames(data.getGrantablePermissionCodes()))),
                            command);
                    return result(id, command);
                });
    }

    /**
     * 受保护配置与普通配置共用完整能力检查及快照写入。
     */
    @Override
    public MutationVO updateProtected(String textId, UpdateProtectedRoleDTO data, String command) {
        long id = Ids.parse(textId);
        return mutations.execute(
                "roles.protected", command, textId, data, data.getExpectedVersion(), true, false, () -> {
                    // 不使用本次新增的能力证明本次操作有权。
                    RolePO role = checked(id, data.getExpectedVersion());
                    RoleProfileDTO profile = BeanUtil.copyProperties(data, RoleProfileDTO.class);
                    validateProfile(role, profile, "roles.protected", true);
                    reviews.verify("PROTECTED_PERMISSIONS", textId, role.getVersion(), data, data.getReviewToken());
                    return saveProfile(role, profile, "roles.protected", data.getReason(), command);
                });
    }

    /**
     * 对个人受保护分配核验角色完整能力、版本和双方动作权限。
     */
    @Override
    public MutationVO protectedAssignment(String textId, ProtectedRoleAssignmentDTO data, String command) {
        long id = Ids.parse(textId);
        return mutations.execute("users.assign", command, textId, data, data.getExpectedVersion(), true, false, () -> {
            // 复用与普通人员分配一致的完整集合校验。
            checkedUser(id, data.getExpectedVersion(), false);
            assignments.validateRoles(id, data.getRoles(), "users.assign", true);
            reviews.verify("PROTECTED_ROLES", textId, data.getExpectedVersion(), data, data.getReviewToken());
            return saveAssignment(id, data.getRoles(), "users.assign", data.getReason(), command);
        });
    }

    /**
     * 使用真实审计中绑定的历史角色版本构造可核验恢复建议。
     */
    @Override
    @Transactional(readOnly = true)
    public RecoveryPreviewVO previewRecovery(String textId, RecoveryPreviewDTO data) {
        guard.shared();
        permissions.require("users.recover");
        long id = Ids.parse(textId);
        checkedUser(id, data.getExpectedVersion(), true);
        var restored = recoveryRoles(id, data.getSourceAuditId());
        var preview = reviews.preview(
                "RECOVER",
                textId,
                data.getExpectedVersion(),
                data,
                List.of(change("roles", "恢复的管理分配", views.roleVersions(id), restored)),
                List.of(new ImpactCountVO("USER", 1)),
                List.of("不会恢复旧任务或数据访问来源"));
        RecoveryPreviewVO result = BeanUtil.copyProperties(preview, RecoveryPreviewVO.class);
        result.setSourceAuditId(data.getSourceAuditId());
        result.setRestoredRoles(restored);
        return result;
    }

    /**
     * 在当前角色与历史内容一致时新建分配，绝不回滚共享角色。
     */
    @Override
    public MutationVO recover(String textId, RecoverUserDTO data, String command) {
        long id = Ids.parse(textId);
        return mutations.execute("users.recover", command, textId, data, data.getExpectedVersion(), true, false, () -> {
            // 历史版本和当前角色在 guard 内再次核对。
            checkedUser(id, data.getExpectedVersion(), true);
            var restored = recoveryRoles(id, data.getSourceAuditId());
            reviews.verify("RECOVER", textId, data.getExpectedVersion(), data, data.getReviewToken());
            return saveAssignment(id, restored, "users.recover", data.getReason(), command);
        });
    }

    /**
     * 核对角色输入、名称唯一及完整最终能力。
     */
    private void validateProfile(RolePO role, RoleProfileDTO profile, String operation, boolean protectedFlow) {
        String key = TextSearch.key(profile.getName().trim());
        if (key.isBlank()
                || key.codePointCount(0, key.length()) > 160
                || profile.getPermissionCodes().isEmpty()) {
            throw BizIllegalException.invalid("角色名称或功能选择不完整");
        }
        if (roles.all().stream()
                .anyMatch(item -> item.getNameKey().equals(key)
                        && (role == null || !item.getId().equals(role.getId())))) {
            throw new BizIllegalException(ErrorCode.IAM_ROLE_NAME_EXISTS, "角色名称已存在");
        }
        permissions.validateCodes(profile.getPermissionCodes(), true);
        profilePermission.requireChange(role, profile.getPermissionCodes(), operation, protectedFlow);
    }

    /**
     * 验证授予目录与当前上限，拒绝通过自己角色间接扩权。
     */
    private void validateGrants(RolePO role, List<String> grants) {
        permissions.require("roles.protected");
        if (new HashSet<>(grants).size() != grants.size()
                || grants.stream().anyMatch(code -> directory.all().stream()
                        .noneMatch(p -> p.getCode().equals(code) && p.isEnabled()))) {
            throw BizIllegalException.invalid("可授予操作含重复或不可用项");
        }
        permissions.checkGrant(grants, "roles.protected");
        permissions.checkSelfRoleChange(role.getId(), roles.permissions(role.getId()), grants, role.getLifecycle());
    }

    /**
     * 生命周期与受保护资格在预览和提交时采用同一检查。
     */
    private void validateLifecycle(RolePO role, String action) {
        String operation = "roles." + action.toLowerCase(Locale.ROOT);
        permissions.require(operation);
        if (permissions.protectedRole(role.getId())) {
            permissions.require("roles.protected");
        }
        if (action.equals("ENABLE")) {
            // 恢复角色使用前重新核对完整实际能力。
            if (!role.getLifecycle().equals("DISABLED")) {
                throw BizIllegalException.state("请先选择停用角色");
            }
            permissions.checkGrant(roles.permissions(role.getId()), operation);
            permissions.checkGrant(roles.grantable(role.getId()), operation);
            permissions.checkSelfRoleChange(
                    role.getId(), roles.permissions(role.getId()), roles.grantable(role.getId()), "ENABLED");
        } else if (action.equals("ARCHIVE")) {
            // 保留的当前分配必须先通过有权流程移出。
            if (!role.getLifecycle().equals("DISABLED") || roleAssignments.memberCount(role.getId()) > 0) {
                throw new BizIllegalException(ErrorCode.REFERENCE_IN_USE, "角色须先停用并移出所有当前成员");
            }
        } else if (!role.getLifecycle().equals("ENABLED")) {
            throw BizIllegalException.state("当前角色状态不允许停用");
        }
    }

    /**
     * 写入名称与完整能力，无变化时保持角色版本不变。
     */
    private MutationVO saveProfile(
            RolePO role, RoleProfileDTO profile, String operation, String reason, String command) {
        var changes = profileChanges(role, profile);
        if (Objects.equals(role.getName(), profile.getName().trim())
                && Objects.equals(role.getDescription(), profile.getDescription())
                && new HashSet<>(roles.permissions(role.getId())).equals(new HashSet<>(profile.getPermissionCodes()))) {
            return result(role.getId(), command);
        }
        profile.setName(profile.getName().trim());
        roles.profile(role.getId(), profile, TextSearch.key(profile.getName()));
        roles.clearPermissions(role.getId());
        profile.getPermissionCodes().forEach(code -> roles.addPermission(role.getId(), code, UserContext.getUser()));
        snapshot(role.getId());
        audit(role.getId(), operation, reason, changes, command);
        return result(role.getId(), command);
    }

    /**
     * 历史角色须仍为正常管理角色，且使用与授予集合均与历史一致。
     */
    private List<RoleVersionRefVO> recoveryRoles(long id, String auditId) {
        List<RoleVersionRefVO> history = audits.recoveryRoles(id, Ids.parse(auditId));
        List<RoleVersionRefVO> restored = new ArrayList<>();
        for (var reference : history) {
            // 历史角色变化只能先处理共享角色，不能在恢复单人时回滚。
            long roleId = Ids.parse(reference.getRoleId());
            var role = views.role(roleId);
            var snapshot = roles.snapshot(roleId, reference.getVersion());
            if (snapshot == null
                    || !role.getLifecycle().equals("ENABLED")
                    || !new HashSet<>(json.strings(snapshot.getPermissionCodes()))
                            .equals(new HashSet<>(roles.permissions(roleId)))
                    || !new HashSet<>(json.strings(snapshot.getGrantablePermissionCodes()))
                            .equals(new HashSet<>(roles.grantable(roleId)))) {
                // 从真实关联目标人员的成功审计读取可核验的历史角色依据。
                throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "历史角色与当前内容不相容，请先核对共享角色");
            }
            if (roles.permissions(roleId).stream()
                    .anyMatch(code -> code.equals("users.recover") || code.equals("roles.protected"))) {
                // 从真实关联目标人员的成功审计读取可核验的历史角色依据。
                restored.add(new RoleVersionRefVO(reference.getRoleId(), role.getVersion()));
            }
        }
        if (restored.isEmpty()) {
            // 从真实关联目标人员的成功审计读取可核验的历史角色依据。
            throw BizIllegalException.state("历史记录中没有可恢复的管理分配");
        }
        var selected = new ArrayList<>(views.roleVersions(id));
        restored.stream()
                .filter(ref -> selected.stream()
                        .noneMatch(current -> current.getRoleId().equals(ref.getRoleId())))
                .forEach(selected::add);
        assignments.validateRoles(id, selected, "users.recover", true);
        return selected;
    }

    /**
     * 新分配保留真实历史与人员版本，并写入一条成功审计。
     */
    private MutationVO saveAssignment(
            long id, List<RoleVersionRefVO> selected, String operation, String reason, String command) {
        var before = views.roleVersions(id);
        if (assignments.rolesMatch(id, selected)) {
            return new MutationVO(
                    command, "user", Long.toString(id), views.user(id).getVersion());
        }
        assignments.applyRoles(id, selected);
        users.touch(id);
        audits.record(AuditWriteDTO.builder()
                .actionCode(operation)
                .targetType("user")
                .targetId(Long.toString(id))
                .targetName(views.user(id).getDisplayName())
                .reason(reason)
                .changes(List.of(change("roles", "个人管理分配", before, selected)))
                .beforeRoles(before)
                .afterRoles(selected)
                .securityVersion(mutations.auditSecurityVersion(true))
                .commandId(command)
                .build());
        return new MutationVO(command, "user", Long.toString(id), views.user(id).getVersion());
    }

    /**
     * 核对目标版本和归档只读约束，历史恢复另要求当前正常。
     */
    private void checkedUser(long id, int version, boolean recovery) {
        var user = views.user(id);
        if (user.getVersion() != version) {
            throw BizIllegalException.conflict();
        }
        if (user.getLifecycle().equals("ARCHIVED")
                || recovery && !user.getLifecycle().equals("ENABLED")) {
            // 停用人员可由受保护配置调整分配，历史恢复不能绕过账号停用状态。
            throw BizIllegalException.state(recovery ? "恢复目标须为正常账号" : "归档人员仅供回看");
        }
    }

    /**
     * 拒绝过期写入和归档角色的再次修改。
     */
    private RolePO checked(long id, int version) {
        RolePO role = views.role(id);
        if (role.getVersion() != version) {
            // 拒绝过期修订和归档对象写入，返回当前权威记录。
            throw BizIllegalException.conflict();
        }
        if (role.getLifecycle().equals("ARCHIVED")) {
            // 拒绝过期修订和归档对象写入，返回当前权威记录。
            throw BizIllegalException.state("归档角色仅供回看");
        }
        return role;
    }

    /**
     * 将接口动作映射为明确的持久化状态。
     */
    private String targetLifecycle(String action) {
        return switch (action) {
            case "DISABLE" -> "DISABLED";
            case "ENABLE" -> "ENABLED";
            case "ARCHIVE" -> "ARCHIVED";
            default -> throw BizIllegalException.invalid("未知角色状态操作");
        };
    }

    /**
     * 每次角色版本变化同时保存使用与授予快照。
     */
    private void snapshot(long id) {
        roles.appendSnapshot(
                id, json.write(roles.permissions(id)), json.write(roles.grantable(id)), UserContext.getUser());
    }

    /**
     * 生成可读的名称、说明及完整功能差异。
     */
    private List<ChangeVO> profileChanges(RolePO role, RoleProfileDTO profile) {
        return List.of(
                change(
                        "name",
                        "角色名称",
                        role == null ? null : role.getName(),
                        profile.getName().trim()),
                change("description", "说明", role == null ? null : role.getDescription(), profile.getDescription()),
                change(
                        "permissionCodes",
                        "使用功能",
                        permissionNames(role == null ? List.of() : roles.permissions(role.getId())),
                        permissionNames(profile.getPermissionCodes())));
    }

    /**
     * 核对和审计展示页面及动作名称，稳定功能代码只参与真实授权和快照。
     */
    private String permissionNames(List<String> codes) {
        Map<String, String> names = new HashMap<>();
        for (var page : permissions.catalog()) {
            // 页面所属关系来自正式目录，不从代码前缀猜测中文含义。
            for (var operation : page.getOperations()) {
                names.put(operation.getCode(), page.getName() + " / " + operation.getName());
            }
        }
        return codes.stream().map(names::get).collect(Collectors.joining("；"));
    }

    /**
     * 将非敏感结构作为核对差异文本返回。
     */
    private ChangeVO change(String field, String label, Object before, Object after) {
        return new ChangeVO(
                field, label, before == null ? null : before.toString(), after == null ? null : after.toString());
    }

    /**
     * 当前目标引用用于命令核实，不缓存敏感请求正文。
     */
    private MutationVO result(long id, String command) {
        return new MutationVO(command, "role", Long.toString(id), views.role(id).getVersion());
    }

    /**
     * 审计与当前角色事实在同一业务事务提交。
     */
    private void audit(long id, String operation, String reason, List<ChangeVO> changes, String command) {
        audits.record(AuditWriteDTO.builder()
                .actionCode(operation)
                .targetType("role")
                .targetId(Long.toString(id))
                .targetName(views.role(id).getName())
                .reason(reason)
                .changes(changes)
                .beforeRoles(List.of())
                .afterRoles(List.of())
                .securityVersion(mutations.auditSecurityVersion(true))
                .commandId(command)
                .build());
    }
}
