package com.dataocean.module.iam.service.impl;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.iam.entity.dto.AddSecondaryMembersDTO;
import com.dataocean.module.iam.entity.dto.CreateDepartmentDTO;
import com.dataocean.module.iam.entity.dto.CreatePreviewDTO;
import com.dataocean.module.iam.entity.dto.DepartmentAddSecondaryProposalDTO;
import com.dataocean.module.iam.entity.dto.DepartmentEnableProposalDTO;
import com.dataocean.module.iam.entity.dto.DepartmentEndMembershipProposalDTO;
import com.dataocean.module.iam.entity.dto.DepartmentImpactPreviewDTO;
import com.dataocean.module.iam.entity.dto.DepartmentMoveChildrenProposalDTO;
import com.dataocean.module.iam.entity.dto.DepartmentMoveMembersProposalDTO;
import com.dataocean.module.iam.entity.dto.DepartmentProfileDTO;
import com.dataocean.module.iam.entity.dto.DepartmentProposalDTO;
import com.dataocean.module.iam.entity.dto.EnableDepartmentDTO;
import com.dataocean.module.iam.entity.dto.EndMembershipDTO;
import com.dataocean.module.iam.entity.dto.MoveChildDepartmentsDTO;
import com.dataocean.module.iam.entity.dto.MoveDepartmentMembersDTO;
import com.dataocean.module.iam.entity.dto.UpdateDepartmentDTO;
import com.dataocean.module.iam.entity.dto.VersionActionDTO;
import com.dataocean.module.iam.entity.po.DepartmentPO;
import com.dataocean.module.iam.entity.po.MembershipPO;
import com.dataocean.module.iam.entity.po.UserPO;
import com.dataocean.module.iam.entity.query.DepartmentTreeQuery;
import com.dataocean.module.iam.entity.query.MemberQuery;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.DepartmentDetailVO;
import com.dataocean.module.iam.entity.vo.DepartmentMemberVO;
import com.dataocean.module.iam.entity.vo.DepartmentTreeVO;
import com.dataocean.module.iam.entity.vo.EntityVersionRefVO;
import com.dataocean.module.iam.entity.vo.ImpactCountVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.UserVersionRefVO;
import com.dataocean.module.iam.mapper.DepartmentMapper;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.MembershipMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.security.IamRequestPermission;
import com.dataocean.module.iam.service.DepartmentService;
import com.dataocean.module.iam.service.IamViewAssembler;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ReviewService;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import cn.hutool.core.bean.BeanUtil;
import lombok.RequiredArgsConstructor;

/**
 * 所有组织写入在全局配置锁内重核祖先与完整集合，阻止并发成环和半批提交。
 */
@Service
@RequiredArgsConstructor
public class DepartmentServiceImpl implements DepartmentService {

    /**
     * 子部门页签使用服务端分页，列表人数和负责人来自当前事实。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<DepartmentDetailVO> children(String textId, PageQuery query) {
        guard.shared();
        permissions.require("departments.view");
        long id = Ids.parse(textId);
        views.department(id);
        Page<DepartmentPO> page = query.toMpPage("sort_order", true);
        page.addOrder(OrderItem.asc("id"));
        return views.departmentDetails(departments.children(page, id));
    }

    /**
     * 当前组织树及聚合版本。
     */
    private final DepartmentMapper departments;

    /**
     * 当前和历史主兼任成员关系。
     */
    private final MembershipMapper memberships;

    /**
     * 被批量操作人员的当前状态和版本。
     */
    private final UserMapper users;

    /**
     * 组织写入的串行化配置锁。
     */
    private final GuardMapper guard;

    /**
     * 当前动作和所属页面的服务端校验。
     */
    private final PermissionService permissions;

    /**
     * 组织及去重人员的视图组装。
     */
    private final IamViewAssembler views;

    /**
     * 幂等、事务审计和 Outbox 的共同提交边界。
     */
    private final MutationService mutations;

    /**
     * 绑定完整提案和版本的影响核对。
     */
    private final ReviewService reviews;

    /**
     * 与真实变化一致的成功审计。
     */
    private final AuditService audits;

    /**
     * 按名称和状态返回必要祖先，不给祖先伪造匹配标记。
     */
    @Override
    @Transactional(readOnly = true)
    public List<DepartmentTreeVO> tree(DepartmentTreeQuery query) {
        guard.shared();
        permissions.require("departments.view");
        return views.tree(query);
    }

    /**
     * 组织层级仅影响展示和成员统计，不代表数据访问继承。
     */
    @Override
    @Transactional(readOnly = true)
    public DepartmentDetailVO detail(String id) {
        guard.shared();
        permissions.require("departments.view");
        return views.departmentDetail(views.department(Ids.parse(id)));
    }

    /**
     * 使用 EXISTS 范围过滤，在 SQL 分页前完成跨部门人员去重。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<DepartmentMemberVO> members(String id, MemberQuery query) {
        guard.shared();
        permissions.require("departments.view");
        var branch = views.branch(Ids.parse(id), query.getIncludeDescendants());
        Page<UserPO> page = query.toMpPage("u.updated_at", false);
        page.addOrder(OrderItem.desc("u.id"));
        return views.departmentMembers(users.page(page, query, branch, null), branch);
    }

    /**
     * 新增前检查完整资料，不创建任何成员或配置事实。
     */
    @Override
    @Transactional(readOnly = true)
    public ImpactPreviewVO previewCreate(CreatePreviewDTO<DepartmentProfileDTO> data) {
        guard.shared();
        permissions.require("departments.create");
        validateProfile(null, data.getProposal());
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
     * 新建部门与审计、防重及通知在同一事务提交。
     */
    @Override
    public MutationVO create(CreateDepartmentDTO data, String command) {
        return mutations.execute("departments.create", command, "create", data, null, true, false, () -> {
            // 正式请求复用核对时相同的业务资料结构。
            DepartmentProfileDTO profile = BeanUtil.copyProperties(data, DepartmentProfileDTO.class);
            validateProfile(null, profile);
            reviews.verify("CREATE", null, null, data, data.getReviewToken());
            profile.setName(profile.getName().trim());
            profile.setCode(profile.getCode().trim());
            long id = departments.create(
                    profile,
                    Ids.nullable(profile.getParentId()),
                    Ids.nullable(profile.getManagerUserId()),
                    UserContext.getUser());
            if (profile.getParentId() != null) {
                // 在核对当前提案后原子建立人员、凭据、组织及获准角色。
                departments.touch(Ids.parse(profile.getParentId()));
            }
            audit(id, "departments.create", null, profileChanges(null, profile), command);
            return result(id, command);
        });
    }

    /**
     * 每种动作核对对应权限、版本、完整成员及影响。
     */
    @Override
    @Transactional(readOnly = true)
    public ImpactPreviewVO preview(String textId, DepartmentImpactPreviewDTO data) {
        guard.shared();
        long id = Ids.parse(textId);
        DepartmentPO department = checked(id, data.getExpectedVersion());
        permissions.require(IamRequestPermission.departmentOperation(data.getAction()));

        // 请求绑定时已按动作选定完整部门提案，后续签名沿用该提案。
        DepartmentProposalDTO proposal = data.getProposal();

        List<ChangeVO> changes;
        switch (data.getAction()) {
            case "UPDATE" -> {
                // 父节点按当前祖先链检查，预览不改动组织。
                var value = (DepartmentProfileDTO) proposal;
                validateProfile(department, value);
                changes = profileChanges(department, value);
            }
            case "ADD_SECONDARY" -> {
                // 整批人员必须仍正常且当前不属于该部门。
                var value = (DepartmentAddSecondaryProposalDTO) proposal;
                validateAdd(department, value.getMembers());
                changes = List.of(change("members", "加入兼任", "", names(value.getMembers())));
            }
            case "END_MEMBERSHIP" -> {
                // 直接移除主部门没有合法中间状态，必须先调岗。
                var value = (DepartmentEndMembershipProposalDTO) proposal;
                var membership = validateEnd(id, value.getMembershipId(), value.getUserExpectedVersion());
                changes = List.of(change(
                        "members", "移出兼任", views.user(membership.getUserId()).getDisplayName(), "已移出"));
            }
            case "MOVE_MEMBERS" -> {
                // 提案必须包含预览时全部直属人员。
                var value = (DepartmentMoveMembersProposalDTO) proposal;
                validateDestination(id, value.getDestination());
                validateCompleteMembers(id, value.getMembers());
                changes = List.of(change(
                        "members",
                        "全体直属成员迁移",
                        names(value.getMembers()),
                        views.department(Ids.parse(value.getDestination().getId()))
                                .getName()));
            }
            case "MOVE_CHILDREN" -> {
                // 子部门集合和目标版本均纳入核对凭据。
                var value = (DepartmentMoveChildrenProposalDTO) proposal;
                validateDestination(id, value.getDestination());
                validateChildren(id, value.getChildren());
                changes = List.of(change(
                        "children",
                        "全部直属子部门迁移",
                        value.getChildren().toString(),
                        views.department(Ids.parse(value.getDestination().getId()))
                                .getName()));
            }
            case "DISABLE", "ENABLE", "ARCHIVE" -> {
                // 恢复组织不恢复已结束的数据来源。
                if (proposal instanceof DepartmentEnableProposalDTO enabled
                        && !Boolean.TRUE.equals(enabled.getReviewedMembers())) {
                    // 恢复部门必须显式确认已复核成员与保留配置。
                    throw BizIllegalException.invalid("请确认已复核成员与保留配置");
                }
                validateLifecycle(department, data.getAction());
                changes = List.of(
                        change("lifecycle", "部门状态", department.getLifecycle(), targetLifecycle(data.getAction())));
            }
            default -> throw BizIllegalException.invalid("未知部门操作");
        }
        return reviews.preview(
                data.getAction(),
                textId,
                data.getExpectedVersion(),
                proposal,
                changes,
                List.of(new ImpactCountVO(
                        "USER", views.memberIds(views.branch(id, true)).size())),
                List.of());
    }

    /**
     * 更新父节点时在锁内再次读取祖先，避免并发互设形成循环。
     */
    @Override
    public MutationVO update(String textId, UpdateDepartmentDTO data, String command) {
        long id = Ids.parse(textId);
        return mutations.execute(
                "departments.edit", command, textId, data, data.getExpectedVersion(), true, false, () -> {
                    // 资料和父节点变更共同成功或回滚。
                    var before = checked(id, data.getExpectedVersion());
                    var profile = BeanUtil.copyProperties(data, DepartmentProfileDTO.class);
                    validateProfile(before, profile);
                    reviews.verify("UPDATE", textId, before.getVersion(), data, data.getReviewToken());
                    if (sameProfile(before, profile)) {
                        // 完整保存获准的人员资料及归属变化，任一失败整份表单回滚。
                        return result(id, command);
                    }
                    departments.profile(
                            id, profile, Ids.nullable(profile.getParentId()), Ids.nullable(profile.getManagerUserId()));
                    if (!Objects.equals(before.getParentId(), Ids.nullable(profile.getParentId()))) {
                        // 父聚合版本随直属子部门变化更新。
                        touchParents(before.getParentId(), Ids.nullable(profile.getParentId()));
                    }
                    audit(id, "departments.edit", null, profileChanges(before, profile), command);
                    return result(id, command);
                });
    }

    /**
     * 先验证整批全部人员，再写入兼任，任一版本变化均整体回滚。
     */
    @Override
    public MutationVO addMembers(String textId, AddSecondaryMembersDTO data, String command) {
        long id = Ids.parse(textId);
        return mutations.execute(
                "departments.add-member", command, textId, data, data.getExpectedVersion(), true, false, () -> {
                    // 验证循环结束前不产生任何成员变更。
                    var department = checked(id, data.getExpectedVersion());
                    validateAdd(department, data.getMembers());
                    reviews.verify("ADD_SECONDARY", textId, department.getVersion(), data, data.getReviewToken());
                    for (var person : data.getMembers()) {
                        memberships.add(Ids.parse(person.getUserId()), id, false, UserContext.getUser());
                        users.touch(Ids.parse(person.getUserId()));
                    }
                    departments.touch(id);
                    audit(
                            id,
                            "departments.add-member",
                            data.getReason(),
                            List.of(change("members", "加入兼任", null, names(data.getMembers()))),
                            command);
                    return result(id, command);
                });
    }

    /**
     * 路径中的关系编号也参与核对，不能替换为另一人的兼任关系。
     */
    @Override
    public MutationVO endMembership(String textId, String membershipId, EndMembershipDTO data, String command) {
        long id = Ids.parse(textId);
        var proposal =
                new DepartmentEndMembershipProposalDTO(membershipId, data.getUserExpectedVersion(), data.getReason());
        return mutations.execute(
                "departments.remove-member",
                command,
                textId + ":" + membershipId,
                data,
                data.getExpectedVersion(),
                true,
                false,
                () -> {
                    // 部门版本与目标人员版本都必须保持核对时状态。
                    checked(id, data.getExpectedVersion());
                    var membership = validateEnd(id, membershipId, data.getUserExpectedVersion());
                    reviews.verify(
                            "END_MEMBERSHIP", textId, data.getExpectedVersion(), proposal, data.getReviewToken());
                    closeMembership(membership.getId());
                    users.touch(membership.getUserId());
                    departments.touch(id);
                    audit(
                            id,
                            "departments.remove-member",
                            data.getReason(),
                            List.of(change(
                                    "members",
                                    "移出兼任",
                                    views.user(membership.getUserId()).getDisplayName(),
                                    "已移出")),
                            command);
                    return result(id, command);
                });
    }

    /**
     * 完整迁移当前直属成员，合并既有目标关系并保持唯一主部门。
     */
    @Override
    public MutationVO moveMembers(String textId, MoveDepartmentMembersDTO data, String command) {
        long id = Ids.parse(textId);
        return mutations.execute(
                "departments.transfer-members", command, textId, data, data.getExpectedVersion(), true, false, () -> {
                    // 锁后完整集合比较，避免只迁移提交时仍未变化的部分。
                    checked(id, data.getExpectedVersion());
                    validateDestination(id, data.getDestination());
                    validateCompleteMembers(id, data.getMembers());
                    reviews.verify("MOVE_MEMBERS", textId, data.getExpectedVersion(), data, data.getReviewToken());
                    long destination = Ids.parse(data.getDestination().getId());
                    for (var relation : memberships.forDepartment(id)) {
                        // 先结束旧主关系，再按原主兼任性质合并目标关系。
                        var existing = memberships.forUser(relation.getUserId()).stream()
                                .filter(row -> row.getDepartmentId() == destination)
                                .findFirst()
                                .orElse(null);
                        closeMembership(relation.getId());
                        if (existing == null) {
                            memberships.add(
                                    relation.getUserId(), destination, relation.isPrimary(), UserContext.getUser());
                        } else if (relation.isPrimary() && !existing.isPrimary()) {
                            closeMembership(existing.getId());
                            memberships.add(relation.getUserId(), destination, true, UserContext.getUser());
                        }
                        users.touch(relation.getUserId());
                    }
                    departments.touch(id);
                    departments.touch(destination);
                    audit(
                            id,
                            "departments.transfer-members",
                            data.getReason(),
                            List.of(change(
                                    "members",
                                    "直属成员迁移",
                                    names(data.getMembers()),
                                    views.department(destination).getName())),
                            command);
                    return result(id, command);
                });
    }

    /**
     * 全部直属非归档子部门一次改父节点，不隐式拆分提交。
     */
    @Override
    public MutationVO moveChildren(String textId, MoveChildDepartmentsDTO data, String command) {
        long id = Ids.parse(textId);
        return mutations.execute(
                "departments.transfer-members", command, textId, data, data.getExpectedVersion(), true, false, () -> {
                    // 目标必须在原子树外，且集合与每个子部门版本一致。
                    checked(id, data.getExpectedVersion());
                    validateDestination(id, data.getDestination());
                    validateChildren(id, data.getChildren());
                    reviews.verify("MOVE_CHILDREN", textId, data.getExpectedVersion(), data, data.getReviewToken());
                    long destination = Ids.parse(data.getDestination().getId());
                    data.getChildren().forEach(child -> departments.move(Ids.parse(child.getId()), destination));
                    departments.touch(id);
                    departments.touch(destination);
                    audit(
                            id,
                            "departments.transfer-members",
                            data.getReason(),
                            List.of(change(
                                    "children",
                                    "直属子部门迁移",
                                    data.getChildren().toString(),
                                    views.department(destination).getName())),
                            command);
                    return result(id, command);
                });
    }

    /**
     * 停用或归档只变更部门事实，不级联停用成员账号。
     */
    @Override
    public MutationVO lifecycle(String textId, String action, VersionActionDTO data, String command) {
        return changeLifecycle(
                textId, action, data.getExpectedVersion(), data.getReason(), data.getReviewToken(), data, command);
    }

    /**
     * 恢复前必须明确确认，恢复后来源配置保持待复核。
     */
    @Override
    public MutationVO enable(String textId, EnableDepartmentDTO data, String command) {
        if (!Boolean.TRUE.equals(data.getReviewedMembers())) {
            // 复核当前归属、角色及来源后恢复账号，不复活旧会话和任务。
            throw BizIllegalException.invalid("请确认已复核成员与保留配置");
        }
        return changeLifecycle(
                textId, "ENABLE", data.getExpectedVersion(), data.getReason(), data.getReviewToken(), data, command);
    }

    /**
     * 共用生命周期的事务、核对与审计，保持动作权限独立。
     */
    private MutationVO changeLifecycle(
            String textId, String action, int version, String reason, String token, Object payload, String command) {
        long id = Ids.parse(textId);
        String operation = IamRequestPermission.departmentOperation(action);
        return mutations.execute(operation, command, textId, payload, version, true, false, () -> {
            // 归档条件必须在提交时重新核对，不能沿用预览计数。
            var department = checked(id, version);
            validateLifecycle(department, action);
            reviews.verify(action, textId, version, payload, token);
            departments.lifecycle(id, targetLifecycle(action), reason);
            if (department.getParentId() != null) {
                departments.touch(department.getParentId());
            }
            audit(
                    id,
                    operation,
                    reason,
                    List.of(change("lifecycle", "部门状态", department.getLifecycle(), targetLifecycle(action))),
                    command);
            return result(id, command);
        });
    }

    /**
     * 检查名称编号、当前父链和负责人，所有引用均按 ID 解析。
     */
    private void validateProfile(DepartmentPO current, DepartmentProfileDTO data) {
        if (departments.exists(Wrappers.<DepartmentPO>lambdaQuery()
                .eq(DepartmentPO::getCode, data.getCode().trim())
                .ne(current != null, DepartmentPO::getId, current == null ? null : current.getId()))) {
            // 使用部门编号唯一索引检查冲突，更新时排除自身。
            throw new BizIllegalException(ErrorCode.IAM_DEPARTMENT_CODE_EXISTS, "部门编号已存在");
        }
        Long parent = Ids.nullable(data.getParentId());
        Set<Long> visited = new HashSet<>();
        while (parent != null) {
            // 整个祖先链都核对，既拒绝自指也拒绝间接循环。
            if (!visited.add(parent) || current != null && parent.equals(current.getId())) {
                throw new BizIllegalException(ErrorCode.IAM_DEPARTMENT_CYCLE, "上级部门不能形成组织循环");
            }
            var ancestor = views.department(parent);
            if (ancestor.getLifecycle().equals("ARCHIVED")) {
                throw BizIllegalException.state("归档部门不能作为上级");
            }
            parent = ancestor.getParentId();
        }
        if (data.getManagerUserId() != null
                && views.user(Ids.parse(data.getManagerUserId())).getLifecycle().equals("ARCHIVED")) {
            throw BizIllegalException.state("归档人员不能作为部门负责人");
        }
    }

    /**
     * 整批人员引用必须唯一、版本匹配且账号正常。
     */
    private void validatePeople(List<UserVersionRefVO> members) {
        Set<String> unique = new HashSet<>();
        for (var reference : members) {
            // 任一无效人员都会在写入前拒绝整个批次。
            if (!unique.add(reference.getUserId())) {
                throw BizIllegalException.invalid("成员不能重复");
            }
            var user = views.user(Ids.parse(reference.getUserId()));
            if (user.getVersion() != reference.getVersion()) {
                throw BizIllegalException.conflict();
            }
            if (!user.getLifecycle().equals("ENABLED")) {
                throw BizIllegalException.state("批量成员必须为正常人员");
            }
        }
    }

    /**
     * 加入兼任不改变原主部门，不能重复建立当前关系。
     */
    private void validateAdd(DepartmentPO department, List<UserVersionRefVO> members) {
        if (!department.getLifecycle().equals("ENABLED") || members.isEmpty() || members.size() > 100) {
            throw BizIllegalException.state("请选择正常部门及 1–100 名成员");
        }
        validatePeople(members);
        if (members.stream().anyMatch(ref -> memberships.forUser(Ids.parse(ref.getUserId())).stream()
                .anyMatch(row -> row.getDepartmentId().equals(department.getId())))) {
            throw BizIllegalException.state("所选人员已在本部门");
        }
    }

    /**
     * 限定关系所属部门、人员版本及兼任性质。
     */
    private MembershipPO validateEnd(long id, String reference, int userVersion) {
        var membership = memberships.findInDepartment(id, Ids.parse(reference));
        if (membership == null) {
            // 范围外或已经结束的关系统一按不可访问处理。
            throw BizIllegalException.missing();
        }
        if (membership.isPrimary()) {
            throw BizIllegalException.state("请先完整调整主部门，再移出原归属");
        }
        if (views.user(membership.getUserId()).getVersion() != userVersion) {
            throw BizIllegalException.conflict();
        }
        return membership;
    }

    /**
     * 迁移目标须正常、版本匹配且在原子树之外。
     */
    private void validateDestination(long id, EntityVersionRefVO destination) {
        var target = checked(Ids.parse(destination.getId()), destination.getVersion());
        if (!target.getLifecycle().equals("ENABLED") || views.branch(id, true).contains(target.getId())) {
            throw new BizIllegalException(ErrorCode.IAM_DEPARTMENT_CYCLE, "迁移目标须为原部门子树以外的正常部门");
        }
    }

    /**
     * 提案必须与当前全体直属成员一致，不接受漏选或新增成员。
     */
    private void validateCompleteMembers(long id, List<UserVersionRefVO> selected) {
        Set<String> actual = new HashSet<>(memberships.forDepartment(id).stream()
                .map(row -> Long.toString(row.getUserId()))
                .toList());
        if (selected.isEmpty()
                || selected.size() != actual.size()
                || !actual.equals(new HashSet<>(
                        selected.stream().map(UserVersionRefVO::getUserId).toList()))) {
            throw new BizIllegalException(ErrorCode.REVIEW_STALE, "当前直属成员集合已变化，请重新核对");
        }
        Set<String> unique = new HashSet<>();
        for (var reference : selected) {
            // 迁移保留停用成员的组织事实，但归档关系不应仍为当前成员。
            var user = views.user(Ids.parse(reference.getUserId()));
            if (!unique.add(reference.getUserId()) || user.getVersion() != reference.getVersion()) {
                throw BizIllegalException.conflict();
            }
        }
    }

    /**
     * 比较完整非归档直属子部门与每个聚合版本。
     */
    private void validateChildren(long id, List<EntityVersionRefVO> selected) {
        var actual = departments.selectList(Wrappers.<DepartmentPO>lambdaQuery()
                .select(DepartmentPO::getId)
                .eq(DepartmentPO::getParentId, id)
                .ne(DepartmentPO::getLifecycle, "ARCHIVED"));
        if (selected.isEmpty()
                || selected.size() != actual.size()
                || !new HashSet<>(
                                selected.stream().map(EntityVersionRefVO::getId).toList())
                        .equals(new HashSet<>(actual.stream()
                                .map(row -> Long.toString(row.getId()))
                                .toList()))) {
            throw new BizIllegalException(ErrorCode.REVIEW_STALE, "直属子部门集合已变化，请重新核对");
        }
        selected.forEach(reference -> checked(Ids.parse(reference.getId()), reference.getVersion()));
    }

    /**
     * 归档前清理当前成员与子部门，停用不影响成员账号。
     */
    private void validateLifecycle(DepartmentPO department, String action) {
        if (action.equals("ARCHIVE")) {
            // B 批配置接入时在同一边界补充真实来源阻断条件。
            if (!department.getLifecycle().equals("DISABLED")
                    || memberships.exists(Wrappers.<MembershipPO>lambdaQuery()
                            .eq(MembershipPO::getDepartmentId, department.getId())
                            .isNull(MembershipPO::getLeftAt))
                    || departments.exists(Wrappers.<DepartmentPO>lambdaQuery()
                            .eq(DepartmentPO::getParentId, department.getId())
                            .ne(DepartmentPO::getLifecycle, "ARCHIVED"))) {
                throw new BizIllegalException(ErrorCode.REFERENCE_IN_USE, "请先停用并处理当前成员及直属子部门");
            }
        } else if (!department.getLifecycle().equals(action.equals("ENABLE") ? "DISABLED" : "ENABLED")) {
            throw BizIllegalException.state("当前部门状态不允许此操作");
        }
    }

    /**
     * 同时检查版本和归档只读边界。
     */
    private DepartmentPO checked(long id, int version) {
        var department = views.department(id);
        if (department.getVersion() != version) {
            // 拒绝过期修订和归档对象写入，返回当前权威记录。
            throw BizIllegalException.conflict();
        }
        if (department.getLifecycle().equals("ARCHIVED")) {
            // 拒绝过期修订和归档对象写入，返回当前权威记录。
            throw BizIllegalException.state("归档部门仅供回看");
        }
        return department;
    }

    /**
     * 将生命周期动作转换为持久化状态。
     */
    private String targetLifecycle(String action) {
        return switch (action) {
            case "ENABLE" -> "ENABLED";
            case "DISABLE" -> "DISABLED";
            case "ARCHIVE" -> "ARCHIVED";
            default -> throw BizIllegalException.invalid("未知部门状态操作");
        };
    }

    /**
     * 判断全部可编辑资料是否未发生变化。
     */
    private boolean sameProfile(DepartmentPO before, DepartmentProfileDTO after) {
        return Objects.equals(before.getName(), after.getName().trim())
                && Objects.equals(before.getCode(), after.getCode().trim())
                && Objects.equals(before.getParentId(), Ids.nullable(after.getParentId()))
                && Objects.equals(before.getManagerUserId(), Ids.nullable(after.getManagerUserId()))
                && Objects.equals(before.getNote(), after.getNote())
                && before.getSortOrder() == after.getSortOrder();
    }

    /**
     * 只结束仍有效的成员关系，缺少实际变更时由业务事务回滚。
     */
    private void closeMembership(long id) {
        if (memberships.end(id, UserContext.getUser()) != 1) {
            // 避免关系已变化时继续递增版本或记录成功审计。
            throw BizIllegalException.conflict();
        }
    }

    /**
     * 资料核对包含负责人和完整上下级变化。
     */
    private List<ChangeVO> profileChanges(DepartmentPO before, DepartmentProfileDTO after) {
        return List.of(
                change("name", "部门名称", before == null ? null : before.getName(), after.getName()),
                change("code", "部门编号", before == null ? null : before.getCode(), after.getCode()),
                change(
                        "parentId",
                        "上级部门",
                        before == null || before.getParentId() == null
                                ? null
                                : views.department(before.getParentId()).getName(),
                        after.getParentId() == null
                                ? null
                                : views.department(Ids.parse(after.getParentId()))
                                        .getName()),
                change(
                        "manager",
                        "负责人",
                        before == null || before.getManagerUserId() == null
                                ? null
                                : views.user(before.getManagerUserId()).getDisplayName(),
                        after.getManagerUserId() == null
                                ? null
                                : views.user(Ids.parse(after.getManagerUserId()))
                                        .getDisplayName()),
                change("note", "说明", before == null ? null : before.getNote(), after.getNote()),
                change(
                        "sortOrder",
                        "排序",
                        before == null ? null : Integer.toString(before.getSortOrder()),
                        Integer.toString(after.getSortOrder())));
    }

    /**
     * 父聚合仅更新一次，保持祖先及直接子部门的版本可信。
     */
    private void touchParents(Long before, Long after) {
        Set<Long> parents = new HashSet<>();
        if (before != null) {
            parents.add(before);
        }
        if (after != null) {
            parents.add(after);
        }
        parents.stream().sorted().forEach(departments::touch);
    }

    /**
     * 从当前人员事实生成可核对的批量成员名称。
     */
    private String names(List<UserVersionRefVO> people) {
        return String.join(
                "、",
                people.stream()
                        .map(ref -> views.user(Ids.parse(ref.getUserId())).getDisplayName())
                        .toList());
    }

    /**
     * 构造仅包含业务可披露内容的差异项。
     */
    private ChangeVO change(String field, String label, String before, String after) {
        return new ChangeVO(field, label, before, after);
    }

    /**
     * 命令结果只保存当前目标引用。
     */
    private MutationVO result(long id, String command) {
        return new MutationVO(
                command, "department", Long.toString(id), views.department(id).getVersion());
    }

    /**
     * 同事务记录组织变化，不将诊断日志代替成功审计。
     */
    private void audit(long id, String operation, String reason, List<ChangeVO> changes, String command) {
        audits.record(AuditWriteDTO.builder()
                .actionCode(operation)
                .targetType("department")
                .targetId(Long.toString(id))
                .targetName(views.department(id).getName())
                .reason(reason)
                .changes(changes)
                .beforeRoles(List.of())
                .afterRoles(List.of())
                .securityVersion(mutations.auditSecurityVersion(true))
                .commandId(command)
                .build());
    }
}
