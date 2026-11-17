package com.dataocean.module.iam.service;

import com.dataocean.module.iam.entity.dto.MembershipSelectionDTO;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;
import java.util.List;

public interface AssignmentService {
    void validateMembership(long userId,MembershipSelectionDTO selection,boolean requireNormal);
    boolean membershipMatches(long userId,MembershipSelectionDTO selection);
    boolean applyMembership(long userId,MembershipSelectionDTO selection);
    void validateRoles(long userId,List<RoleVersionRefVO> selected,String operation,boolean protectedFlow);
    boolean rolesMatch(long userId,List<RoleVersionRefVO> selected);
    boolean applyRoles(long userId,List<RoleVersionRefVO> selected);
}
