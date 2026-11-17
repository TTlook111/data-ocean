package com.dataocean.module.iam.service;

import com.dataocean.common.PageVO;
import com.dataocean.module.iam.entity.dto.CreateUserDTO;
import com.dataocean.module.iam.entity.dto.CreateUserPreviewDTO;
import com.dataocean.module.iam.entity.dto.EnableUserDTO;
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

public interface UserService {
    PageVO<UserRowVO> list(UserQuery query);
    UserDetailVO detail(String id);
    ImpactPreviewVO previewCreate(CreateUserPreviewDTO data);
    MutationVO create(CreateUserDTO data,String command);
    ImpactPreviewVO preview(String id,UserImpactPreviewDTO data);
    MutationVO update(String id,UpdateUserDTO data,String command);
    MutationVO transfer(String id,TransferUserDTO data,String command);
    MutationVO assignRoles(String id,RoleAssignmentDTO data,String command);
    MutationVO disable(String id,VersionActionDTO data,String command);
    MutationVO enable(String id,EnableUserDTO data,String command);
    MutationVO archive(String id,VersionActionDTO data,String command);
    MutationVO resetPassword(String id,ResetPasswordDTO data,String command);
    AccountVO account();
    AccountVO contact(UpdateContactDTO data,String command);
    List<DepartmentOptionVO> departmentOptions(DepartmentOptionQuery query);
    List<RoleOptionVO> roleOptions(RoleOptionQuery query);
    List<UserOptionVO> userOptions(UserOptionQuery query);
}
