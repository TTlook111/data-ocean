package com.dataocean.module.user.service;

import com.dataocean.module.user.req.DepartmentCreateRequest;
import com.dataocean.module.user.vo.DepartmentTreeVO;

import java.util.List;

public interface DepartmentService {

    List<DepartmentTreeVO> tree();

    Long createDepartment(DepartmentCreateRequest request);

    void deleteDepartment(Long id);
}
