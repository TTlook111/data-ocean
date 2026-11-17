package com.dataocean.module.iam.service;

import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.module.iam.entity.dto.AddSecondaryMembersDTO;
import com.dataocean.module.iam.entity.dto.CreateDepartmentDTO;
import com.dataocean.module.iam.entity.dto.CreatePreviewDTO;
import com.dataocean.module.iam.entity.dto.DepartmentImpactPreviewDTO;
import com.dataocean.module.iam.entity.dto.DepartmentProfileDTO;
import com.dataocean.module.iam.entity.dto.EnableDepartmentDTO;
import com.dataocean.module.iam.entity.dto.EndMembershipDTO;
import com.dataocean.module.iam.entity.dto.MoveChildDepartmentsDTO;
import com.dataocean.module.iam.entity.dto.MoveDepartmentMembersDTO;
import com.dataocean.module.iam.entity.dto.UpdateDepartmentDTO;
import com.dataocean.module.iam.entity.dto.VersionActionDTO;
import com.dataocean.module.iam.entity.query.DepartmentTreeQuery;
import com.dataocean.module.iam.entity.query.MemberQuery;
import com.dataocean.module.iam.entity.vo.DepartmentDetailVO;
import com.dataocean.module.iam.entity.vo.DepartmentMemberVO;
import com.dataocean.module.iam.entity.vo.DepartmentTreeVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;

import java.util.List;

/**
 * 维护组织树、主兼任成员和整批迁移，不生成业务数据访问。
 */
public interface DepartmentService {

    /**
     * 子部门页签按数据库当前直属关系分页，并返回真实负责人及人数。
     */
    PageVO<DepartmentDetailVO> children(String id, PageQuery query);

    /**
     * 返回匹配节点及保持层级所需的祖先。
     */
    List<DepartmentTreeVO> tree(DepartmentTreeQuery query);

    /**
     * 读取组织详情及按人员去重的成员统计。
     */
    DepartmentDetailVO detail(String id);

    /**
     * 按直接或后代范围对人员去重后分页。
     */
    PageVO<DepartmentMemberVO> members(String id, MemberQuery query);

    /**
     * 核对新增部门的父节点、负责人和编号。
     */
    ImpactPreviewVO previewCreate(CreatePreviewDTO<DepartmentProfileDTO> data);

    /**
     * 创建根部门或子部门并写入成功审计。
     */
    MutationVO create(CreateDepartmentDTO data, String command);

    /**
     * 核对具体动作的完整成员集合和当前版本。
     */
    ImpactPreviewVO preview(String id, DepartmentImpactPreviewDTO data);

    /**
     * 在组织配置锁内修改资料并阻止直接或间接循环。
     */
    MutationVO update(String id, UpdateDepartmentDTO data, String command);

    /**
     * 整批加入兼任，保持每个人原有主部门。
     */
    MutationVO addMembers(String id, AddSecondaryMembersDTO data, String command);

    /**
     * 仅结束指定兼任，不允许直接移除当前主部门。
     */
    MutationVO endMembership(String id, String membershipId, EndMembershipDTO data, String command);

    /**
     * 核对全体直属成员后整批迁移。
     */
    MutationVO moveMembers(String id, MoveDepartmentMembersDTO data, String command);

    /**
     * 核对全部非归档直属子部门后整批迁移。
     */
    MutationVO moveChildren(String id, MoveChildDepartmentsDTO data, String command);

    /**
     * 停用、复核恢复或归档组织，保留成员账号状态。
     */
    MutationVO lifecycle(String id, String action, VersionActionDTO data, String command);

    /**
     * 恢复组织时显式确认成员和保留配置已经复核。
     */
    MutationVO enable(String id, EnableDepartmentDTO data, String command);
}
