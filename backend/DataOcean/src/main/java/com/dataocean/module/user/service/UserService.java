package com.dataocean.module.user.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.user.query.UserQueryRequest;
import com.dataocean.module.user.req.UserCreateRequest;
import com.dataocean.module.user.req.UserUpdateRequest;
import com.dataocean.module.user.vo.UserVO;

public interface UserService {

    Long createUser(UserCreateRequest request);

    void updateUser(Long id, UserUpdateRequest request);

    void deleteUser(Long id);

    UserVO getUserById(Long id);

    Page<UserVO> listUsers(UserQueryRequest request);

    void updateStatus(Long id, Integer status);
}
