package com.dataocean.module.iam.service;

import com.dataocean.common.PageVO;
import com.dataocean.module.iam.entity.dto.CreatePreviewDTO;
import com.dataocean.module.iam.entity.dto.CreateRoleDTO;
import com.dataocean.module.iam.entity.dto.ProtectedRoleAssignmentDTO;
import com.dataocean.module.iam.entity.dto.RecoverUserDTO;
import com.dataocean.module.iam.entity.dto.RecoveryPreviewDTO;
import com.dataocean.module.iam.entity.dto.RoleImpactPreviewDTO;
import com.dataocean.module.iam.entity.dto.RoleProfileDTO;
import com.dataocean.module.iam.entity.dto.UpdateGrantLimitDTO;
import com.dataocean.module.iam.entity.dto.UpdateProtectedRoleDTO;
import com.dataocean.module.iam.entity.dto.UpdateRoleDTO;
import com.dataocean.module.iam.entity.dto.VersionActionDTO;
import com.dataocean.module.iam.entity.query.PersonQuery;
import com.dataocean.module.iam.entity.query.RoleQuery;
import com.dataocean.module.iam.entity.vo.GrantLimitVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.RecoveryPreviewVO;
import com.dataocean.module.iam.entity.vo.RoleDetailVO;
import com.dataocean.module.iam.entity.vo.RoleRowVO;
import com.dataocean.module.iam.entity.vo.UserRowVO;

/**
 * 提供角色配置、当前成员、受保护调整和有历史依据的分配恢复。
 */
public interface RoleService {

    /**
     * 按查询条件与白名单排序分页读取角色。
     */
    PageVO<RoleRowVO> list(RoleQuery query);

    /**
     * 读取角色当前使用能力，不隐式返回授予上限。
     */
    RoleDetailVO detail(String id);

    /**
     * 分页读取当前成员，包含保留分配的停用人员。
     */
    PageVO<UserRowVO> members(String id, PersonQuery query);

    /**
     * 核对新增角色的完整功能提案。
     */
    ImpactPreviewVO previewCreate(CreatePreviewDTO<RoleProfileDTO> data);

    /**
     * 创建普通角色并保存首版角色快照。
     */
    MutationVO create(CreateRoleDTO data, String command);

    /**
     * 按动作核对版本、能力和全部当前成员影响。
     */
    ImpactPreviewVO preview(String id, RoleImpactPreviewDTO data);

    /**
     * 修改普通配置，受保护能力保持原状。
     */
    MutationVO update(String id, UpdateRoleDTO data, String command);

    /**
     * 在配置锁内变更角色生命周期并检查恢复途径。
     */
    MutationVO lifecycle(String id, String action, VersionActionDTO data, String command);

    /**
     * 单独核权后查看角色可授予集合。
     */
    GrantLimitVO grantLimit(String id);

    /**
     * 调整后续授予上限，保留别人已有使用权。
     */
    MutationVO updateGrantLimit(String id, UpdateGrantLimitDTO data, String command);

    /**
     * 经受保护核对调整完整使用能力。
     */
    MutationVO updateProtected(String id, UpdateProtectedRoleDTO data, String command);

    /**
     * 经受保护核对调整个人角色分配。
     */
    MutationVO protectedAssignment(String id, ProtectedRoleAssignmentDTO data, String command);

    /**
     * 从指定审计及角色快照构造恢复提案。
     */
    RecoveryPreviewVO previewRecovery(String id, RecoveryPreviewDTO data);

    /**
     * 重核历史和当前角色内容后建立新的管理分配。
     */
    MutationVO recover(String id, RecoverUserDTO data, String command);
}
