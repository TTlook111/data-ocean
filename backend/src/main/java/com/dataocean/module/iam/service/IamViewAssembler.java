package com.dataocean.module.iam.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.TextSearch;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.iam.entity.po.DepartmentPO;
import com.dataocean.module.iam.entity.po.MembershipPO;
import com.dataocean.module.iam.entity.po.RoleAssignmentPO;
import com.dataocean.module.iam.entity.po.RolePO;
import com.dataocean.module.iam.entity.po.UserPO;
import com.dataocean.module.iam.entity.query.DepartmentTreeQuery;
import com.dataocean.module.iam.entity.vo.AccountVO;
import com.dataocean.module.iam.entity.vo.DepartmentDetailVO;
import com.dataocean.module.iam.entity.vo.DepartmentMemberVO;
import com.dataocean.module.iam.entity.vo.DepartmentRefVO;
import com.dataocean.module.iam.entity.vo.DepartmentTreeVO;
import com.dataocean.module.iam.entity.vo.MembershipRefVO;
import com.dataocean.module.iam.entity.vo.RoleDetailVO;
import com.dataocean.module.iam.entity.vo.RoleRefVO;
import com.dataocean.module.iam.entity.vo.RoleRowVO;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;
import com.dataocean.module.iam.entity.vo.UserAffiliationVO;
import com.dataocean.module.iam.entity.vo.UserDetailVO;
import com.dataocean.module.iam.entity.vo.UserRefVO;
import com.dataocean.module.iam.entity.vo.UserRowVO;
import com.dataocean.module.iam.mapper.DepartmentMapper;
import com.dataocean.module.iam.mapper.MembershipMapper;
import com.dataocean.module.iam.mapper.RoleAssignmentMapper;
import com.dataocean.module.iam.mapper.RoleMapper;
import com.dataocean.module.iam.mapper.UserMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import cn.hutool.core.bean.BeanUtil;
import lombok.RequiredArgsConstructor;

/**
 * 本模块的明确 VO 组装；权限由所属业务服务核验，PO 不作为接口返回值。
 */
@Component
@RequiredArgsConstructor
public class IamViewAssembler {

    /**
     * 人员资料、生命周期与受控凭据的访问依赖。
     */
    private final UserMapper users;

    /**
     * 当前角色、使用与授予集合及分配历史的访问依赖。
     */
    private final RoleMapper roles;

    /**
     * 查询当前角色分配关系。
     */
    private final RoleAssignmentMapper roleAssignments;

    /**
     * 部门树和聚合版本的持久化访问依赖。
     */
    private final DepartmentMapper departments;

    /**
     * 主兼任关系及其结束历史的持久化能力。
     */
    private final MembershipMapper memberships;

    /**
     * 当前操作者实际使用能力与显式授予资格的业务服务。
     */
    private final PermissionService permissions;

    /**
     * 统一的明确类型 JSON 编解码适配。
     */
    private final JsonCodec json;

    /**
     * 按稳定人员标识读取当前权威记录，不返回默认管理身份。
     */
    public UserPO user(long id) {
        var user = users.selectById(id);
        if (user == null) {
            throw BizIllegalException.missing();
        }
        return user;
    }

    /**
     * 按稳定标识核对并读取当前角色。
     */
    public RolePO role(long id) {
        var role = roles.selectById(id);
        if (role == null) {
            throw BizIllegalException.missing();
        }
        return role;
    }

    /**
     * 读取并核对指定稳定部门记录，不按名称引用。
     */
    public DepartmentPO department(long id) {
        var department = departments.selectById(id);
        if (department == null) {
            throw BizIllegalException.missing();
        }
        return department;
    }

    /**
     * 查询原主体本次命令的可披露提交事实，并重核当前动作资格。
     */
    public String status(UserPO data) {
        if (!data.getLifecycle().equals("ENABLED")) {
            return data.getLifecycle();
        }
        if (data.isMustChangePassword()) {
            return data.getFirstLoginAt() == null ? "PENDING_FIRST_LOGIN" : "PASSWORD_CHANGE_REQUIRED";
        }
        return "ENABLED";
    }

    /**
     * 组装人员标识、姓名、登录名和当前展示状态的安全引用。
     */
    public UserRefVO userRef(long id) {
        return userRef(user(id));
    }

    /**
     * 用已加载的人员记录组装安全引用。
     */
    private UserRefVO userRef(UserPO data) {
        return new UserRefVO(Ids.text(data.getId()), data.getUsername(), data.getDisplayName(), status(data));
    }

    /**
     * 组装不带数据授权含义的安全部门引用。
     */
    public DepartmentRefVO departmentRef(long id) {
        return departmentRef(department(id));
    }

    /**
     * 用已加载的部门记录组装安全引用。
     */
    private DepartmentRefVO departmentRef(DepartmentPO data) {
        return new DepartmentRefVO(Ids.text(data.getId()), data.getCode(), data.getName(), data.getLifecycle());
    }

    /**
     * 组装带当前修订及受保护标记的安全角色引用。
     */
    public RoleRefVO roleRef(long id) {
        return roleRef(role(id), permissions.protectedRole(id));
    }

    /**
     * 用已加载的角色和受保护标记组装安全引用。
     */
    private RoleRefVO roleRef(RolePO data, boolean protectedRole) {
        return new RoleRefVO(
                Ids.text(data.getId()),
                data.getCode(),
                data.getName(),
                data.getLifecycle(),
                data.getVersion(),
                protectedRole);
    }

    /**
     * 按当前关系或归档快照组装人员归属，不将历史当作当前授权。
     */
    public UserAffiliationVO affiliation(UserPO data) {
        if (data.getLifecycle().equals("ARCHIVED")) {
            // 归档人员显示归档前的关系快照。
            return json.read(data.getArchivedAffiliation(), UserAffiliationVO.class);
        }

        var selected = memberships.forUser(data.getId()).stream()
                .map(item -> membershipRef(item, departmentRef(item.getDepartmentId())))
                .toList();
        var roleRefs = roleAssignments.assignments(data.getId()).stream()
                .map(item -> roleRef(item.getRoleId()))
                .toList();
        return affiliation(selected, roleRefs);
    }

    /**
     * 将已加载的当前关系按主任、兼任和角色分组。
     */
    private UserAffiliationVO affiliation(List<MembershipRefVO> selected, List<RoleRefVO> roleRefs) {
        DepartmentRefVO primary = selected.stream()
                .filter(MembershipRefVO::isPrimary)
                .findFirst()
                .map(MembershipRefVO::getDepartment)
                .orElse(null);
        var secondary = selected.stream()
                .filter(item -> !item.isPrimary())
                .map(MembershipRefVO::getDepartment)
                .toList();
        return new UserAffiliationVO(primary, secondary, roleRefs);
    }

    /**
     * 先加载本页人员的全部关系，再按原分页顺序组装摘要。
     */
    public PageVO<UserRowVO> userRows(IPage<UserPO> page) {
        var relations = userRelations(page.getRecords());
        return PageVO.from(page, person -> userRow(person, affiliation(person, relations)));
    }

    /**
     * 复用本页关系组装人员行，并保留命中当前部门范围的全部主兼任关系。
     */
    public PageVO<DepartmentMemberVO> departmentMembers(IPage<UserPO> page, List<Long> branch) {
        var relations = userRelations(page.getRecords());
        var selected = Set.copyOf(branch);
        return PageVO.from(page, person -> {
            // 返回其完整人员摘要，但单独标明本次部门搜索命中的关系。
            var row = userRow(person, affiliation(person, relations));
            var result = BeanUtil.copyProperties(row, DepartmentMemberVO.class);
            result.setMatchingMemberships(relations.memberships().getOrDefault(person.getId(), List.of()).stream()
                    .filter(item ->
                            selected.contains(Ids.parse(item.getDepartment().getId())))
                    .toList());
            return result;
        });
    }

    /**
     * 批量加载本页人员的组织和角色关系，并一次解析关联的展示引用。
     */
    private UserRelations userRelations(List<UserPO> people) {
        var userIds = people.stream().map(UserPO::getId).toList();

        if (userIds.isEmpty()) {
            // 空页无需访问任何关联表。
            return new UserRelations(Map.of(), Map.of());
        }

        var selected = memberships.forUsers(userIds);
        var departmentIds = selected.stream().map(MembershipPO::getDepartmentId).collect(Collectors.toSet());
        var departmentRefs = departmentIds.isEmpty()
                ? Map.<Long, DepartmentRefVO>of()
                : departments.selectByIds(departmentIds).stream()
                        .collect(Collectors.toMap(DepartmentPO::getId, this::departmentRef));

        var assigned = roleAssignments.assignmentsForUsers(userIds);
        var roleIds = assigned.stream().map(RoleAssignmentPO::getRoleId).collect(Collectors.toSet());
        var protectedRoles = permissions.protectedRoles(roleIds);
        var roleRefs = roleIds.isEmpty()
                ? Map.<Long, RoleRefVO>of()
                : roles.selectByIds(roleIds).stream()
                        .collect(Collectors.toMap(
                                RolePO::getId, role -> roleRef(role, protectedRoles.contains(role.getId()))));

        // 按人员分组，保留 Mapper 返回的主兼任和角色顺序。
        var membershipsByUser = selected.stream()
                .collect(Collectors.groupingBy(
                        MembershipPO::getUserId,
                        Collectors.mapping(
                                row -> membershipRef(row, departmentRefs.get(row.getDepartmentId())),
                                Collectors.toList())));
        var rolesByUser = assigned.stream()
                .collect(Collectors.groupingBy(
                        RoleAssignmentPO::getUserId,
                        Collectors.mapping(row -> roleRefs.get(row.getRoleId()), Collectors.toList())));
        return new UserRelations(membershipsByUser, rolesByUser);
    }

    /**
     * 组装当前成员关系及用于展示的部门引用。
     */
    private MembershipRefVO membershipRef(MembershipPO row, DepartmentRefVO department) {
        return new MembershipRefVO(
                Ids.text(row.getId()), department, row.isPrimary(), Times.instant(row.getJoinedAt()));
    }

    /**
     * 归档人员沿用历史快照，其余人员使用本页已加载的当前关系。
     */
    private UserAffiliationVO affiliation(UserPO data, UserRelations relations) {
        if (data.getLifecycle().equals("ARCHIVED")) {
            // 快照中的名称和角色修订保留归档时的值。
            return json.read(data.getArchivedAffiliation(), UserAffiliationVO.class);
        }

        return affiliation(
                relations.memberships().getOrDefault(data.getId(), List.of()),
                relations.roles().getOrDefault(data.getId(), List.of()));
    }

    /**
     * 一页人员当前关系的分组结果。
     *
     * @param memberships 按人员编号分组的当前主兼任关系
     * @param roles 按人员编号分组的当前角色引用
     */
    private record UserRelations(Map<Long, List<MembershipRefVO>> memberships, Map<Long, List<RoleRefVO>> roles) {}

    /**
     * 组装列表使用的当前或归档主兼任与角色摘要。
     */
    private UserRowVO userRow(UserPO data, UserAffiliationVO affiliation) {
        return new UserRowVO(
                Ids.text(data.getId()),
                data.getUsername(),
                data.getDisplayName(),
                affiliation.getPrimaryDepartment(),
                affiliation.getSecondaryDepartments(),
                affiliation.getRoles(),
                status(data),
                data.getVersion(),
                Times.instant(data.getUpdatedAt()));
    }

    /**
     * 组装人员详情及必要状态时间，归档归属从当时快照读取。
     */
    public UserDetailVO userDetail(UserPO data) {
        UserDetailVO result = BeanUtil.copyProperties(userRow(data, affiliation(data)), UserDetailVO.class);
        result.setLifecycle(data.getLifecycle());
        result.setMustChangePassword(data.isMustChangePassword());
        result.setPhone(data.getPhone());
        result.setEmail(data.getEmail());
        result.setManagementNote(data.getManagementNote());
        result.setFirstLoginAt(Times.instant(data.getFirstLoginAt()));
        result.setDisabledAt(Times.instant(data.getDisabledAt()));
        result.setArchivedAt(Times.instant(data.getArchivedAt()));
        return result;
    }

    /**
     * 读取当前登录本人的资料和组织角色，不要求管理他人的权限。
     */
    public AccountVO account(UserPO data) {
        var affiliation = affiliation(data);
        return new AccountVO(
                Ids.text(data.getId()),
                data.getUsername(),
                data.getDisplayName(),
                status(data),
                affiliation.getPrimaryDepartment(),
                affiliation.getSecondaryDepartments(),
                affiliation.getRoles(),
                data.getPhone(),
                data.getEmail(),
                data.getVersion());
    }

    /**
     * 取得人员当前角色引用及实际角色修订，供核对和历史审计。
     */
    public List<RoleVersionRefVO> roleVersions(long id) {
        return roleAssignments.assignments(id).stream()
                .map(item -> new RoleVersionRefVO(
                        Ids.text(item.getRoleId()), role(item.getRoleId()).getVersion()))
                .toList();
    }

    /**
     * 组装角色状态、实际成员数量及当前修订的列表视图。
     */
    public RoleRowVO roleRow(RolePO data) {
        return roleRow(data, permissions.protectedRole(data.getId()), roleAssignments.memberCount(data.getId()));
    }

    /**
     * 批量读取本页角色的受保护标记及统计，保留数据库分页顺序。
     */
    public PageVO<RoleRowVO> roleRows(IPage<RolePO> page) {
        var ids = page.getRecords().stream().map(RolePO::getId).toList();

        if (ids.isEmpty()) {
            // 空页直接沿用数据库分页元数据。
            return PageVO.from(page, this::roleRow);
        }

        var protectedRoles = permissions.protectedRoles(ids);
        var memberCounts = roleAssignments.memberCounts(ids).stream()
                .collect(Collectors.toMap(
                        RoleAssignmentMapper.MemberCount::roleId, RoleAssignmentMapper.MemberCount::memberCount));
        return PageVO.from(
                page,
                role -> roleRow(
                        role,
                        protectedRoles.contains(role.getId()),
                        Math.toIntExact(memberCounts.getOrDefault(role.getId(), 0L))));
    }

    /**
     * 用已加载的辅助字段组装角色列表行。
     */
    private RoleRowVO roleRow(RolePO data, boolean protectedRole, int memberCount) {
        return new RoleRowVO(
                Ids.text(data.getId()),
                data.getCode(),
                data.getName(),
                data.getDescription(),
                data.getLifecycle(),
                data.isBuiltin(),
                protectedRole,
                memberCount,
                data.getVersion(),
                Times.instant(data.getUpdatedAt()));
    }

    /**
     * 在角色行摘要上补充完整当前使用功能，不顺带披露授予上限。
     */
    public RoleDetailVO roleDetail(RolePO data) {
        RoleDetailVO result = BeanUtil.copyProperties(roleRow(data), RoleDetailVO.class);
        result.setPermissionCodes(roles.permissions(data.getId()));
        return result;
    }

    /**
     * 取得部门自身或完整后代标识，先核对组织存在性。
     */
    public List<Long> branch(long id, boolean descendants) {
        department(id);
        return descendants ? branch(id, departments.all()) : List.of(id);
    }

    /**
     * 从同一批部门记录找出指定部门及其完整后代。
     */
    private List<Long> branch(long id, List<DepartmentPO> all) {
        Set<Long> selected = new HashSet<>();
        selected.add(id);
        boolean changed = true;
        while (changed) {
            // 逐层纳入后代，直到当前范围不再扩大。
            changed = false;
            for (var item : all) {
                // 父部门已经命中时纳入这个部门。
                if (item.getParentId() != null && selected.contains(item.getParentId())) {
                    // 只有新增部门才需要继续扫描后续层级。
                    changed |= selected.add(item.getId());
                }
            }
        }
        return selected.stream().sorted().toList();
    }

    /**
     * 按稳定人员标识合并指定部门范围成员并去重。
     */
    public Set<Long> memberIds(List<Long> branch) {
        return memberships.forDepartments(branch).stream()
                .map(MembershipPO::getUserId)
                .collect(Collectors.toSet());
    }

    /**
     * 组装当前部门资料、负责人和按人员去重的范围统计。
     */
    public DepartmentDetailVO departmentDetail(DepartmentPO data) {
        var all = branch(data.getId(), true);
        var parent = data.getParentId() == null ? null : departmentRef(data.getParentId());
        var manager = data.getManagerUserId() == null ? null : userRef(data.getManagerUserId());
        return departmentDetail(data, parent, manager, all, membersByDepartment(Set.copyOf(all)));
    }

    /**
     * 用一次层级读取和一批关系查询组装子部门分页统计与负责人引用。
     */
    public PageVO<DepartmentDetailVO> departmentDetails(IPage<DepartmentPO> page) {
        var records = page.getRecords();
        if (records.isEmpty()) {
            // 没有当前页部门时无需读取层级和成员。
            return PageVO.from(page, this::departmentDetail);
        }

        var nodes = departments.all();
        var nodesById = nodes.stream().collect(Collectors.toMap(DepartmentPO::getId, this::departmentRef));
        var branches =
                records.stream().collect(Collectors.toMap(DepartmentPO::getId, data -> branch(data.getId(), nodes)));
        var scope = branches.values().stream().flatMap(List::stream).collect(Collectors.toSet());
        var members = membersByDepartment(scope);

        var managerIds = records.stream()
                .map(DepartmentPO::getManagerUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        var managers = managerIds.isEmpty()
                ? Map.<Long, UserRefVO>of()
                : users.selectByIds(managerIds).stream().collect(Collectors.toMap(UserPO::getId, this::userRef));

        return PageVO.from(
                page,
                data -> departmentDetail(
                        data,
                        data.getParentId() == null ? null : nodesById.get(data.getParentId()),
                        data.getManagerUserId() == null ? null : managers.get(data.getManagerUserId()),
                        branches.get(data.getId()),
                        members));
    }

    /**
     * 将当前任职关系按部门聚合为去重人员编号。
     */
    private Map<Long, Set<Long>> membersByDepartment(Set<Long> scope) {
        return memberships.forDepartments(scope).stream()
                .collect(Collectors.groupingBy(
                        MembershipPO::getDepartmentId,
                        Collectors.mapping(MembershipPO::getUserId, Collectors.toSet())));
    }

    /**
     * 分别统计本部门、后代及二者合并范围内的去重人数。
     */
    private DepartmentDetailVO departmentDetail(
            DepartmentPO data,
            DepartmentRefVO parent,
            UserRefVO manager,
            List<Long> branch,
            Map<Long, Set<Long>> members) {
        var direct = members.getOrDefault(data.getId(), Set.of());
        var descendants = branch.stream()
                .filter(id -> !id.equals(data.getId()))
                .flatMap(id -> members.getOrDefault(id, Set.of()).stream())
                .collect(Collectors.toSet());

        int descendantCount = descendants.size();
        descendants.addAll(direct);
        return new DepartmentDetailVO(
                Ids.text(data.getId()),
                data.getCode(),
                data.getName(),
                parent,
                manager,
                data.getNote(),
                data.getSortOrder(),
                data.getLifecycle(),
                data.getVersion(),
                direct.size(),
                descendantCount,
                descendants.size(),
                data.isAccessReviewRequired(),
                Times.instant(data.getUpdatedAt()));
    }

    /**
     * 按当前查询返回命中部门和必要祖先，层级不代表访问继承。
     */
    public List<DepartmentTreeVO> tree(DepartmentTreeQuery query) {
        if (query.getLifecycle() != null
                && !List.of("ENABLED", "DISABLED", "ARCHIVED").contains(query.getLifecycle())) {
            throw BizIllegalException.invalid("部门状态不正确");
        }
        List<DepartmentPO> nodes = departments.all();
        Map<Long, DepartmentPO> byId = new HashMap<>();
        nodes.forEach(node -> byId.put(node.getId(), node));
        Map<Long, Set<Long>> memberCounts = new HashMap<>();
        for (var membership : memberships.current()) {
            // 同一人员在祖先范围内仅计一次，不把主兼任关系数量当成人数。
            Long current = membership.getDepartmentId();
            Set<Long> visited = new HashSet<>();
            while (current != null) {
                if (!visited.add(current)) {
                    throw new IllegalStateException("Department hierarchy contains a cycle");
                }
                memberCounts
                        .computeIfAbsent(current, ignored -> new HashSet<>())
                        .add(membership.getUserId());
                current = byId.get(current).getParentId();
            }
        }
        return treeChildren(null, nodes, query, new HashSet<>(), memberCounts);
    }

    /**
     * 按父节点递归组装有序组织层级，拒绝循环并保留真实匹配标记。
     */
    private List<DepartmentTreeVO> treeChildren(
            Long parent,
            List<DepartmentPO> all,
            DepartmentTreeQuery query,
            Set<Long> ancestors,
            Map<Long, Set<Long>> memberCounts) {
        List<DepartmentTreeVO> result = new ArrayList<>();
        for (var node : all) {
            if (Objects.equals(node.getParentId(), parent)) {
                if (ancestors.contains(node.getId())) {
                    throw new IllegalStateException("Department hierarchy contains a cycle");
                }
                Set<Long> next = new HashSet<>(ancestors);
                next.add(node.getId());
                var children = treeChildren(node.getId(), all, query, next, memberCounts);
                boolean matched = matches(node.getName(), query.getName())
                        && (query.getLifecycle() == null || node.getLifecycle().equals(query.getLifecycle()));
                if (matched || !children.isEmpty()) {
                    result.add(new DepartmentTreeVO(
                            Ids.text(node.getId()),
                            node.getCode(),
                            node.getName(),
                            node.getLifecycle(),
                            node.getVersion(),
                            matched,
                            children,
                            memberCounts.getOrDefault(node.getId(), Set.of()).size()));
                }
            }
        }
        return result;
    }

    /**
     * 按当前用途比较规范文本或以恒定时间比较受控摘要。
     */
    public static boolean matches(String text, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        if (text == null) {
            return false;
        }
        String normalized = TextSearch.key(text);
        for (String term : TextSearch.key(query).split("\\s+")) {
            if (!normalized.contains(term)) {
                return false;
            }
        }
        return true;
    }
}
