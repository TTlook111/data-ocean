package com.dataocean.module.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.user.dto.DepartmentCreateRequest;
import com.dataocean.module.user.dto.DepartmentTreeVO;
import com.dataocean.module.user.entity.SysDepartment;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.DepartmentMapper;
import com.dataocean.module.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;

    public List<DepartmentTreeVO> tree() {
        List<SysDepartment> departments = departmentMapper.selectList(new LambdaQueryWrapper<SysDepartment>()
                .eq(SysDepartment::getStatus, 1)
                .orderByAsc(SysDepartment::getSortOrder)
                .orderByAsc(SysDepartment::getId));
        Map<Long, DepartmentTreeVO> nodeMap = new LinkedHashMap<>();
        for (SysDepartment department : departments) {
            nodeMap.put(department.getId(), DepartmentTreeVO.builder()
                    .id(department.getId())
                    .parentId(department.getParentId())
                    .deptName(department.getDeptName())
                    .deptCode(department.getDeptCode())
                    .sortOrder(department.getSortOrder())
                    .children(new ArrayList<>())
                    .build());
        }
        List<DepartmentTreeVO> roots = new ArrayList<>();
        for (DepartmentTreeVO node : nodeMap.values()) {
            if (node.getParentId() != null && nodeMap.containsKey(node.getParentId())) {
                nodeMap.get(node.getParentId()).getChildren().add(node);
            } else {
                roots.add(node);
            }
        }
        sortTree(roots);
        return roots;
    }

    @Transactional
    public Long createDepartment(DepartmentCreateRequest request) {
        if (request.getParentId() != null && departmentMapper.selectById(request.getParentId()) == null) {
            throw new BusinessException("上级部门不存在");
        }
        Long sameCode = departmentMapper.selectCount(new LambdaQueryWrapper<SysDepartment>()
                .eq(SysDepartment::getDeptCode, request.getDeptCode()));
        if (sameCode > 0) {
            throw new BusinessException("部门编码已存在");
        }
        SysDepartment department = new SysDepartment();
        department.setParentId(request.getParentId());
        department.setDeptName(request.getDeptName());
        department.setDeptCode(request.getDeptCode());
        department.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        department.setStatus(1);
        departmentMapper.insert(department);
        return department.getId();
    }

    @Transactional
    public void deleteDepartment(Long id) {
        Long userCount = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getDepartmentId, id));
        Long childCount = departmentMapper.selectCount(new LambdaQueryWrapper<SysDepartment>().eq(SysDepartment::getParentId, id));
        if (userCount > 0 || childCount > 0) {
            throw new BusinessException("非空部门禁止删除");
        }
        departmentMapper.deleteById(id);
    }

    private void sortTree(List<DepartmentTreeVO> nodes) {
        nodes.sort(Comparator.comparing(DepartmentTreeVO::getSortOrder, Comparator.nullsLast(Integer::compareTo)));
        nodes.forEach(node -> sortTree(node.getChildren()));
    }
}
