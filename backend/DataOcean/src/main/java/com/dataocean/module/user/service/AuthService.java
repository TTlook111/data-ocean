package com.dataocean.module.user.service;
import com.dataocean.module.user.entity.dto.ChangePasswordDTO;
import com.dataocean.module.user.entity.dto.LoginDTO;
import com.dataocean.module.user.entity.dto.ProfileUpdateDTO;
import com.dataocean.module.user.entity.vo.CurrentUserVO;
import com.dataocean.module.user.entity.vo.LoginVO;

public interface AuthService {

    LoginVO login(LoginDTO request);

    void logout(String authorizationHeader);

    CurrentUserVO currentUserInfo();

    void changePassword(ChangePasswordDTO request);

    void updateProfile(ProfileUpdateDTO request);
}
