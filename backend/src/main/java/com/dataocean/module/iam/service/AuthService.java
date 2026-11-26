package com.dataocean.module.iam.service;

import com.dataocean.common.security.IamPrincipal;
import com.dataocean.module.iam.entity.dto.ChangePasswordDTO;
import com.dataocean.module.iam.entity.dto.InitialPasswordDTO;
import com.dataocean.module.iam.entity.dto.LoginDTO;
import com.dataocean.module.iam.entity.vo.CurrentSessionVO;

/**
 * 校验凭据和维护认证事实，HTTP 会话由控制器通过安全框架保存。
 */
public interface AuthService {

    /**
     * 在短事务外验密，再锁定重核凭据修订与账号资格。
     */
    IamPrincipal authenticate(LoginDTO data, String remoteAddress);

    /**
     * 根据当前权威状态返回受限或完整登录身份。
     */
    CurrentSessionVO current(IamPrincipal principal, long authenticatedAt);

    /**
     * 完成首次改密并递增认证版本，使全部旧会话失效。
     */
    void initialPassword(InitialPasswordDTO data);

    /**
     * 核对原密码后更新本人凭据，不复用通用命令重放。
     */
    void changePassword(ChangePasswordDTO data);
}
