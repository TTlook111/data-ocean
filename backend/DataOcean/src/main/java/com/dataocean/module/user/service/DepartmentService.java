package com.dataocean.module.user.service;

import com.dataocean.module.user.entity.dto.DepartmentCreateDTO;
import com.dataocean.module.user.entity.vo.DepartmentTreeVO;

import java.util.List;

public interface DepartmentService {

    List<DepartmentTreeVO> tree();

    Long createDepartment(DepartmentCreateDTO request);

    void deleteDepartment(Long id);
}
