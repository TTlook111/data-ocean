package com.dataocean.module.user.service;

import com.dataocean.common.security.LoginUser;
import com.dataocean.module.user.entity.req.ChangePasswordRequest;
import com.dataocean.module.user.entity.req.LoginRequest;
import com.dataocean.module.user.entity.req.ProfileUpdateRequest;
import com.dataocean.module.user.entity.vo.CurrentUserResponse;
import com.dataocean.module.user.entity.vo.LoginResponse;

public interface AuthService {

    LoginResponse login(LoginRequest request);

    void logout(String authorizationHeader);

    LoginUser currentUser();

    CurrentUserResponse currentUserInfo();

    void changePassword(ChangePasswordRequest request);

    void updateProfile(ProfileUpdateRequest request);
}
