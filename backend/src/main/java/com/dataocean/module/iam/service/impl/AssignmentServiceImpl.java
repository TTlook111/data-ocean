package com.dataocean.module.iam.service.impl;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.iam.entity.dto.MembershipSelectionDTO;
import com.dataocean.module.iam.entity.po.MembershipPO;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;
import com.dataocean.module.iam.mapper.DepartmentMapper;
import com.dataocean.module.iam.mapper.MembershipMapper;
import com.dataocean.module.iam.mapper.RoleMapper;
import com.dataocean.module.iam.service.AssignmentService;
import com.dataocean.module.iam.service.PermissionService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AssignmentServiceImpl implements AssignmentService {
    private final MembershipMapper memberships;
    private final DepartmentMapper departments;
    private final RoleMapper roles;
    private final PermissionService permissions;
    public void validateMembership(long id,MembershipSelectionDTO selection,boolean requireNormal) {
        long primary=Ids.parse(selection.getPrimaryDepartmentId());
        List<Long> secondary=selection.getSecondaryDepartmentIds().stream().map(Ids::parse).toList();
        if(secondary.contains(primary) || new HashSet<>(secondary).size()!=secondary.size())throw BizIllegalException.invalid("主部门与兼任部门不能重复");
        var existing=id==0?List.<MembershipPO>of():memberships.forUser(id);
        var selected=new ArrayList<Long>(secondary);selected.add(primary);
        for(long departmentId:selected) {
            var department=departments.find(departmentId);if(department==null)throw BizIllegalException.missing();
            boolean unchanged=existing.stream().anyMatch(row->row.getDepartmentId()==departmentId && row.isPrimary()==(departmentId==primary));
            // 停用部门只保留原组织事实；新增、调岗或账号恢复时须选择正常部门。
            if(!department.getLifecycle().equals("ENABLED") && (requireNormal || !unchanged))throw BizIllegalException.state("新增或恢复的部门归属必须选择正常部门");
        }
    }
    public boolean membershipMatches(long id,MembershipSelectionDTO selection) {
        var existing=memberships.forUser(id);
        return existing.size()==selection.getSecondaryDepartmentIds().size()+1 && existing.stream().allMatch(row->
            row.isPrimary()?Ids.text(row.getDepartmentId()).equals(selection.getPrimaryDepartmentId()):selection.getSecondaryDepartmentIds().contains(Ids.text(row.getDepartmentId())));
    }
    public boolean applyMembership(long id,MembershipSelectionDTO selection) {
        if(membershipMatches(id,selection))return false;
        long primary=Ids.parse(selection.getPrimaryDepartmentId());
        Set<Long> desired=new HashSet<>();desired.add(primary);selection.getSecondaryDepartmentIds().forEach(value->desired.add(Ids.parse(value)));
        Set<Long> unchanged=new HashSet<>(),affected=new HashSet<>();
        for(var row:memberships.forUser(id)) {
            if(desired.contains(row.getDepartmentId()) && row.isPrimary()==(row.getDepartmentId()==primary)) unchanged.add(row.getDepartmentId());
            else {memberships.end(row.getId(),UserContext.getUser());affected.add(row.getDepartmentId());}
        }
        for(long department:desired)if(!unchanged.contains(department)) {memberships.add(id,department,department==primary,UserContext.getUser());affected.add(department);}
        affected.stream().sorted().forEach(departments::touch);
        return true;
    }
    public boolean rolesMatch(long id,List<RoleVersionRefVO> selected) {
        Set<Long> expected=new HashSet<>();selected.forEach(ref->expected.add(Ids.parse(ref.getRoleId())));
        return expected.equals(new HashSet<>(roles.assignments(id).stream().map(row->row.getRoleId()).toList()));
    }
    public void validateRoles(long id,List<RoleVersionRefVO> selected,String operation,boolean protectedFlow) {
        Set<Long> before=new HashSet<>(id==0?List.of():roles.assignments(id).stream().map(row->row.getRoleId()).toList());
        Set<Long> after=new HashSet<>();List<String> complete=new ArrayList<>();
        for(var ref:selected) {
            long roleId=Ids.parse(ref.getRoleId());if(!after.add(roleId))throw BizIllegalException.invalid("角色不能重复选择");
            var role=roles.find(roleId);if(role==null)throw BizIllegalException.missing();
            if(role.getVersion()!=ref.getVersion())throw BizIllegalException.conflict();
            if(role.getLifecycle().equals("ARCHIVED") || (!role.getLifecycle().equals("ENABLED") && !before.contains(roleId)))throw BizIllegalException.state("停用或归档角色不能新分配");
            complete.addAll(roles.permissions(roleId));complete.addAll(roles.grantable(roleId));
        }
        Set<Long> oldProtected=new HashSet<>(),newProtected=new HashSet<>();
        before.stream().filter(permissions::protectedRole).forEach(oldProtected::add);
        after.stream().filter(permissions::protectedRole).forEach(newProtected::add);
        if(!protectedFlow && !oldProtected.equals(newProtected))throw new BizIllegalException(ErrorCode.PROTECTED_FLOW_REQUIRED,"受保护角色分配须单独核验");
        permissions.checkGrant(complete.stream().distinct().toList(),operation);
        if(protectedFlow) {permissions.require("roles.protected");permissions.checkGrant(complete.stream().distinct().toList(),"roles.protected");}
        if(id==UserContext.getUser())permissions.checkSelfAssignment(selected);
    }
    public boolean applyRoles(long id,List<RoleVersionRefVO> selected) {
        if(rolesMatch(id,selected))return false;
        Set<Long> after=new HashSet<>();selected.forEach(ref->after.add(Ids.parse(ref.getRoleId())));
        Set<Long> before=new HashSet<>();
        for(var row:roles.assignments(id)) {before.add(row.getRoleId());if(!after.contains(row.getRoleId()))roles.endAssignment(row.getId(),UserContext.getUser());}
        for(var ref:selected) {long role=Ids.parse(ref.getRoleId());if(!before.contains(role))roles.assign(id,role,ref.getVersion(),UserContext.getUser());}
        return true;
    }
}
