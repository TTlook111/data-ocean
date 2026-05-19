package com.dataocean.module.user.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.user.entity.query.UserQuery;
import com.dataocean.module.user.entity.dto.UserCreateDTO;
import com.dataocean.module.user.entity.dto.UserUpdateDTO;
import com.dataocean.module.user.entity.vo.UserVO;

public interface UserService {

    Long createUser(UserCreateDTO request);

    void updateUser(Long id, UserUpdateDTO request);

    void deleteUser(Long id);

    UserVO getUserById(Long id);

    Page<UserVO> listUsers(UserQuery request);

    void updateStatus(Long id, Integer status);

    String resetPassword(Long id);
}
