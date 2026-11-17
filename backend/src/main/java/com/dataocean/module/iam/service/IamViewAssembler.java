package com.dataocean.module.iam.service;

import com.dataocean.common.Ids;
import com.dataocean.common.TextSearch;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.iam.entity.po.DepartmentPO;
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
import com.dataocean.module.iam.mapper.RoleMapper;
import com.dataocean.module.iam.mapper.UserMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        var user = users.find(id);
        if (user == null) {
            throw BizIllegalException.missing();
        }
        return user;
    }

    /**
     * 按稳定标识核对并读取当前角色。
     */
    public RolePO role(long id) {
        var role = roles.find(id);
        if (role == null) {
            throw BizIllegalException.missing();
        }
        return role;
    }

    /**
     * 读取并核对指定稳定部门记录，不按名称引用。
     */
    public DepartmentPO department(long id) {
        var department = departments.find(id);
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
        var data = user(id);
        return new UserRefVO(Ids.text(data.getId()), data.getUsername(), data.getDisplayName(), status(data));
    }

    /**
     * 组装不带数据授权含义的安全部门引用。
     */
    public DepartmentRefVO departmentRef(long id) {
        var data = department(id);
        return new DepartmentRefVO(Ids.text(data.getId()), data.getCode(), data.getName(), data.getLifecycle());
    }

    /**
     * 组装带当前修订及受保护标记的安全角色引用。
     */
    public RoleRefVO roleRef(long id) {
        var data = role(id);
        return new RoleRefVO(
                Ids.text(data.getId()),
                data.getCode(),
                data.getName(),
                data.getLifecycle(),
                data.getVersion(),
                permissions.protectedRole(id));
    }

    /**
     * 按当前关系或归档快照组装人员归属，不将历史当作当前授权。
     */
    public UserAffiliationVO affiliation(UserPO data) {
        if (data.getLifecycle().equals("ARCHIVED")) {
            return json.read(data.getArchivedAffiliation(), UserAffiliationVO.class);
        }
        var selected = memberships.forUser(data.getId());
        DepartmentRefVO primary = selected.stream()
                .filter(item -> item.isPrimary())
                .findFirst()
                .map(item -> departmentRef(item.getDepartmentId()))
                .orElse(null);
        var secondary = selected.stream()
                .filter(item -> !item.isPrimary())
                .map(item -> departmentRef(item.getDepartmentId()))
                .toList();
        var roleRefs = roles.assignments(data.getId()).stream()
                .map(item -> roleRef(item.getRoleId()))
                .toList();
        return new UserAffiliationVO(primary, secondary, roleRefs);
    }

    /**
     * 组装列表使用的当前或归档主兼任与角色摘要。
     */
    public UserRowVO userRow(UserPO data) {
        var affiliation = affiliation(data);
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
        UserDetailVO result = BeanUtil.copyProperties(userRow(data), UserDetailVO.class);
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
        return roles.assignments(id).stream()
                .map(item -> new RoleVersionRefVO(
                        Ids.text(item.getRoleId()), role(item.getRoleId()).getVersion()))
                .toList();
    }

    /**
     * 组装角色状态、实际成员数量及当前修订的列表视图。
     */
    public RoleRowVO roleRow(RolePO data) {
        return new RoleRowVO(
                Ids.text(data.getId()),
                data.getCode(),
                data.getName(),
                data.getDescription(),
                data.getLifecycle(),
                data.isBuiltin(),
                permissions.protectedRole(data.getId()),
                roles.memberCount(data.getId()),
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
        Set<Long> selected = new HashSet<>();
        selected.add(id);
        if (descendants) {
            List<DepartmentPO> all = departments.all();
            boolean changed = true;
            while (changed) {
                changed = false;
                for (var item : all) {
                    if (item.getParentId() != null && selected.contains(item.getParentId())) {
                        changed |= selected.add(item.getId());
                    }
                }
            }
        }
        return selected.stream().sorted().toList();
    }

    /**
     * 按稳定人员标识合并指定部门范围成员并去重。
     */
    public Set<Long> memberIds(List<Long> branch) {
        Set<Long> ids = new HashSet<>();
        for (long id : branch) {
            memberships.forDepartment(id).forEach(item -> ids.add(item.getUserId()));
        }
        return ids;
    }

    /**
     * 组装当前部门资料、负责人和按人员去重的范围统计。
     */
    public DepartmentDetailVO departmentDetail(DepartmentPO data) {
        var all = branch(data.getId(), true);
        int direct = memberIds(List.of(data.getId())).size();
        int descendants = memberIds(
                        all.stream().filter(id -> !id.equals(data.getId())).toList())
                .size();
        var result = new DepartmentDetailVO(
                Ids.text(data.getId()),
                data.getCode(),
                data.getName(),
                data.getParentId() == null ? null : departmentRef(data.getParentId()),
                data.getManagerUserId() == null ? null : userRef(data.getManagerUserId()),
                data.getNote(),
                data.getSortOrder(),
                data.getLifecycle(),
                data.getVersion(),
                direct,
                descendants,
                memberIds(all).size(),
                data.isAccessReviewRequired(),
                Times.instant(data.getUpdatedAt()));
        return result;
    }

    /**
     * 为去重人员保留本次部门范围实际命中的全部主兼任关系。
     */
    public DepartmentMemberVO departmentMember(UserPO data, List<Long> branch) {
        DepartmentMemberVO result = BeanUtil.copyProperties(userRow(data), DepartmentMemberVO.class);
        result.setMatchingMemberships(memberships.forUser(data.getId()).stream()
                .filter(item -> branch.contains(item.getDepartmentId()))
                .map(item -> new MembershipRefVO(
                        Ids.text(item.getId()),
                        departmentRef(item.getDepartmentId()),
                        item.isPrimary(),
                        Times.instant(item.getJoinedAt())))
                .toList());
        return result;
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
