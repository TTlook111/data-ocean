package com.dataocean.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.user.req.DepartmentCreateRequest;
import com.dataocean.module.user.vo.DepartmentTreeVO;
import com.dataocean.module.user.entity.SysDepartment;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.DepartmentMapper;
import com.dataocean.module.user.mapper.UserMapper;
import com.dataocean.module.user.service.DepartmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DepartmentServiceImpl implements DepartmentService {

    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;

    @Override
    public List<DepartmentTreeVO> tree() {
        log.debug("查询部门树");
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
        log.debug("部门树查询完成 departmentCount={} rootCount={}", departments.size(), roots.size());
        return roots;
    }

    @Transactional
    @Override
    public Long createDepartment(DepartmentCreateRequest request) {
        log.info("开始创建部门 deptCode={} deptName={} parentId={}",
                request.getDeptCode(), request.getDeptName(), request.getParentId());
        if (request.getParentId() != null && departmentMapper.selectById(request.getParentId()) == null) {
            log.warn("创建部门被拒绝：上级部门不存在 parentId={}", request.getParentId());
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
        log.info("部门创建成功 departmentId={} deptCode={}", department.getId(), department.getDeptCode());
        return department.getId();
    }

    @Transactional
    @Override
    public void deleteDepartment(Long id) {
        log.info("开始删除部门 departmentId={}", id);
        Long userCount = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getDepartmentId, id));
        Long childCount = departmentMapper.selectCount(new LambdaQueryWrapper<SysDepartment>().eq(SysDepartment::getParentId, id));
        if (userCount > 0 || childCount > 0) {
            // 数据库不创建外键，非空部门保护统一在业务层校验。
            log.warn("删除部门被拒绝：部门非空 departmentId={} userCount={} childCount={}",
                    id, userCount, childCount);
            throw new BusinessException("非空部门禁止删除");
        }
        departmentMapper.deleteById(id);
        log.info("部门删除成功 departmentId={}", id);
    }

    private void sortTree(List<DepartmentTreeVO> nodes) {
        nodes.sort(Comparator.comparing(DepartmentTreeVO::getSortOrder, Comparator.nullsLast(Integer::compareTo)));
        nodes.forEach(node -> sortTree(node.getChildren()));
    }
}
