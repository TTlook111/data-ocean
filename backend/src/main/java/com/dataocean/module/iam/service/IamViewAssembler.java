package com.dataocean.module.iam.service;

import cn.hutool.core.bean.BeanUtil;
import com.dataocean.common.Ids;
import com.dataocean.common.TextSearch;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
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
import com.dataocean.infrastructure.JsonCodec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 本模块的明确 VO 组装；权限由所属业务服务核验，PO 不作为接口返回值。 */
@Component
@RequiredArgsConstructor
public class IamViewAssembler {
    private final UserMapper users;
    private final RoleMapper roles;
    private final DepartmentMapper departments;
    private final MembershipMapper memberships;
    private final PermissionService permissions;
    private final JsonCodec json;

    public UserPO user(long id) { var user=users.find(id);if(user==null)throw BizIllegalException.missing();return user; }
    public RolePO role(long id) { var role=roles.find(id);if(role==null)throw BizIllegalException.missing();return role; }
    public DepartmentPO department(long id) { var department=departments.find(id);if(department==null)throw BizIllegalException.missing();return department; }
    public String status(UserPO data) {
        if(!data.getLifecycle().equals("ENABLED"))return data.getLifecycle();
        if(data.isMustChangePassword())return data.getFirstLoginAt()==null?"PENDING_FIRST_LOGIN":"PASSWORD_CHANGE_REQUIRED";
        return "ENABLED";
    }
    public UserRefVO userRef(long id) {var data=user(id);return new UserRefVO(Ids.text(data.getId()),data.getUsername(),data.getDisplayName(),status(data));}
    public DepartmentRefVO departmentRef(long id) {var data=department(id);return new DepartmentRefVO(Ids.text(data.getId()),data.getCode(),data.getName(),data.getLifecycle());}
    public RoleRefVO roleRef(long id) {var data=role(id);return new RoleRefVO(Ids.text(data.getId()),data.getCode(),data.getName(),data.getLifecycle(),data.getVersion(),permissions.protectedRole(id));}
    public UserAffiliationVO affiliation(UserPO data) {
        if(data.getLifecycle().equals("ARCHIVED"))return json.read(data.getArchivedAffiliation(),UserAffiliationVO.class);
        var selected=memberships.forUser(data.getId());
        DepartmentRefVO primary=selected.stream().filter(item->item.isPrimary()).findFirst().map(item->departmentRef(item.getDepartmentId())).orElse(null);
        var secondary=selected.stream().filter(item->!item.isPrimary()).map(item->departmentRef(item.getDepartmentId())).toList();
        var roleRefs=roles.assignments(data.getId()).stream().map(item->roleRef(item.getRoleId())).toList();
        return new UserAffiliationVO(primary,secondary,roleRefs);
    }
    public UserRowVO userRow(UserPO data) {
        var affiliation=affiliation(data);
        return new UserRowVO(Ids.text(data.getId()),data.getUsername(),data.getDisplayName(),affiliation.getPrimaryDepartment(),affiliation.getSecondaryDepartments(),affiliation.getRoles(),status(data),data.getVersion(),Times.instant(data.getUpdatedAt()));
    }
    public UserDetailVO userDetail(UserPO data) {
        UserDetailVO result=BeanUtil.copyProperties(userRow(data),UserDetailVO.class);
        result.setLifecycle(data.getLifecycle());result.setMustChangePassword(data.isMustChangePassword());result.setPhone(data.getPhone());result.setEmail(data.getEmail());result.setManagementNote(data.getManagementNote());
        result.setFirstLoginAt(Times.instant(data.getFirstLoginAt()));result.setDisabledAt(Times.instant(data.getDisabledAt()));result.setArchivedAt(Times.instant(data.getArchivedAt()));
        return result;
    }
    public AccountVO account(UserPO data) {
        var affiliation=affiliation(data);
        return new AccountVO(Ids.text(data.getId()),data.getUsername(),data.getDisplayName(),status(data),affiliation.getPrimaryDepartment(),affiliation.getSecondaryDepartments(),affiliation.getRoles(),data.getPhone(),data.getEmail(),data.getVersion());
    }
    public List<RoleVersionRefVO> roleVersions(long id) {return roles.assignments(id).stream().map(item->new RoleVersionRefVO(Ids.text(item.getRoleId()),role(item.getRoleId()).getVersion())).toList();}
    public RoleRowVO roleRow(RolePO data) {return new RoleRowVO(Ids.text(data.getId()),data.getCode(),data.getName(),data.getDescription(),data.getLifecycle(),data.isBuiltin(),permissions.protectedRole(data.getId()),roles.memberCount(data.getId()),data.getVersion(),Times.instant(data.getUpdatedAt()));}
    public RoleDetailVO roleDetail(RolePO data) {
        RoleDetailVO result=BeanUtil.copyProperties(roleRow(data),RoleDetailVO.class);result.setPermissionCodes(roles.permissions(data.getId()));return result;
    }
    public List<Long> branch(long id,boolean descendants) {
        department(id);
        Set<Long> selected=new HashSet<>();selected.add(id);
        if(descendants) {
            List<DepartmentPO> all=departments.all();
            boolean changed=true;
            while(changed) {changed=false;for(var item:all)if(item.getParentId()!=null && selected.contains(item.getParentId()))changed|=selected.add(item.getId());}
        }
        return selected.stream().sorted().toList();
    }
    public Set<Long> memberIds(List<Long> branch) {
        Set<Long> ids=new HashSet<>();
        for(long id:branch)memberships.forDepartment(id).forEach(item->ids.add(item.getUserId()));
        return ids;
    }
    public DepartmentDetailVO departmentDetail(DepartmentPO data) {
        var all=branch(data.getId(),true);
        int direct=memberIds(List.of(data.getId())).size();
        int descendants=memberIds(all.stream().filter(id->!id.equals(data.getId())).toList()).size();
        var result=new DepartmentDetailVO(Ids.text(data.getId()),data.getCode(),data.getName(),data.getParentId()==null?null:departmentRef(data.getParentId()),data.getManagerUserId()==null?null:userRef(data.getManagerUserId()),data.getNote(),data.getSortOrder(),data.getLifecycle(),data.getVersion(),direct,descendants,memberIds(all).size(),data.isAccessReviewRequired(),Times.instant(data.getUpdatedAt()));
        return result;
    }
    public DepartmentMemberVO departmentMember(UserPO data,List<Long> branch) {
        DepartmentMemberVO result=BeanUtil.copyProperties(userRow(data),DepartmentMemberVO.class);
        result.setMatchingMemberships(memberships.forUser(data.getId()).stream().filter(item->branch.contains(item.getDepartmentId())).map(item->new MembershipRefVO(Ids.text(item.getId()),departmentRef(item.getDepartmentId()),item.isPrimary(),Times.instant(item.getJoinedAt()))).toList());
        return result;
    }
    public List<DepartmentTreeVO> tree(DepartmentTreeQuery query) {
        if(query.getLifecycle()!=null && !List.of("ENABLED","DISABLED","ARCHIVED").contains(query.getLifecycle()))throw BizIllegalException.invalid("部门状态不正确");
        return treeChildren(null,departments.all(),query,new HashSet<>());
    }
    private List<DepartmentTreeVO> treeChildren(Long parent,List<DepartmentPO> all,DepartmentTreeQuery query,Set<Long> ancestors) {
        List<DepartmentTreeVO> result=new ArrayList<>();
        for(var node:all)if(Objects.equals(node.getParentId(),parent)) {
            if(ancestors.contains(node.getId()))throw new IllegalStateException("Department hierarchy contains a cycle");
            Set<Long> next=new HashSet<>(ancestors);next.add(node.getId());
            var children=treeChildren(node.getId(),all,query,next);
            boolean matched=matches(node.getName(),query.getName()) && (query.getLifecycle()==null || node.getLifecycle().equals(query.getLifecycle()));
            if(matched || !children.isEmpty())result.add(new DepartmentTreeVO(Ids.text(node.getId()),node.getCode(),node.getName(),node.getLifecycle(),node.getVersion(),matched,children));
        }
        return result;
    }
    public static boolean matches(String text,String query) {
        if(query==null || query.isBlank())return true;
        if(text==null)return false;
        String normalized=TextSearch.key(text);
        for(String term:TextSearch.key(query).split("\\s+"))if(!normalized.contains(term))return false;
        return true;
    }
}
