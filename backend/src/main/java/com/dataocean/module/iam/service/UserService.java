package com.dataocean.module.iam.service;

import com.dataocean.common.PageVO;
import com.dataocean.module.iam.entity.dto.CreatePreviewDTO;
import com.dataocean.module.iam.entity.dto.CreateUserDTO;
import com.dataocean.module.iam.entity.dto.EnableUserDTO;
import com.dataocean.module.iam.entity.dto.NewUserProfileDTO;
import com.dataocean.module.iam.entity.dto.ResetPasswordDTO;
import com.dataocean.module.iam.entity.dto.RoleAssignmentDTO;
import com.dataocean.module.iam.entity.dto.TransferUserDTO;
import com.dataocean.module.iam.entity.dto.UpdateContactDTO;
import com.dataocean.module.iam.entity.dto.UpdateUserDTO;
import com.dataocean.module.iam.entity.dto.UserImpactPreviewDTO;
import com.dataocean.module.iam.entity.dto.VersionActionDTO;
import com.dataocean.module.iam.entity.query.DepartmentOptionQuery;
import com.dataocean.module.iam.entity.query.RoleOptionQuery;
import com.dataocean.module.iam.entity.query.UserOptionQuery;
import com.dataocean.module.iam.entity.query.UserQuery;
import com.dataocean.module.iam.entity.vo.AccountVO;
import com.dataocean.module.iam.entity.vo.DepartmentOptionVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.RoleOptionVO;
import com.dataocean.module.iam.entity.vo.UserDetailVO;
import com.dataocean.module.iam.entity.vo.UserOptionVO;
import com.dataocean.module.iam.entity.vo.UserRowVO;

import java.util.List;

/**
 * 人员资料的业务边界，调用者通过明确类型协作。
 */
public interface UserService {

    /**
     * 在当前权限和查询范围内过滤、统计并返回有效分页记录。
     */
    PageVO<UserRowVO> list(UserQuery query);

    /**
     * 按当前查看资格读取完整业务详情或真实历史快照。
     */
    UserDetailVO detail(String id);

    /**
     * 校验创建提案并生成绑定操作者和完整内容的核对凭据。
     */
    ImpactPreviewVO previewCreate(CreatePreviewDTO<NewUserProfileDTO> data);

    /**
     * 在核对当前提案后原子建立人员、凭据、组织及获准角色。
     */
    MutationVO create(CreateUserDTO data, String command);

    /**
     * 核对当前动作的完整提案、影响与版本，不在预览阶段写入业务事实。
     */
    ImpactPreviewVO preview(String id, UserImpactPreviewDTO data);

    /**
     * 完整保存获准的人员资料及归属变化，任一失败整份表单回滚。
     */
    MutationVO update(String id, UpdateUserDTO data, String command);

    /**
     * 完整调整人员主兼任归属，不同时更改个人角色。
     */
    MutationVO transfer(String id, TransferUserDTO data, String command);

    /**
     * 按当前版本和完整能力直接保存个人角色分配。
     */
    MutationVO assignRoles(String id, RoleAssignmentDTO data, String command);

    /**
     * 按核对凭据和当前版本停用账号，并可靠通知旧认证失效。
     */
    MutationVO disable(String id, VersionActionDTO data, String command);

    /**
     * 复核当前归属、角色及来源后恢复账号，不复活旧会话和任务。
     */
    MutationVO enable(String id, EnableUserDTO data, String command);

    /**
     * 核对生命周期及未处理关联后归档人员并保存历史归属。
     */
    MutationVO archive(String id, VersionActionDTO data, String command);

    /**
     * 管理员重置目标初始凭据并保留恢复途径检查及全会话失效。
     */
    MutationVO resetPassword(String id, ResetPasswordDTO data, String command);

    /**
     * 读取当前登录本人的资料和组织角色，不要求管理他人的权限。
     */
    AccountVO account();

    /**
     * 以当前本人身份和版本原子保存联系方式，允许显式清空。
     */
    AccountVO contact(UpdateContactDTO data, String command);

    /**
     * 按具体动作核验并返回可选部门及原已选只读项。
     */
    List<DepartmentOptionVO> departmentOptions(DepartmentOptionQuery query);

    /**
     * 按用途、当前状态、完整能力及授予上限生成可选择或只读保留角色。
     */
    List<RoleOptionVO> roleOptions(RoleOptionQuery query);

    /**
     * 按部门负责人或加入成员的具体动作资格返回可选人员。
     */
    List<UserOptionVO> userOptions(UserOptionQuery query);
}
