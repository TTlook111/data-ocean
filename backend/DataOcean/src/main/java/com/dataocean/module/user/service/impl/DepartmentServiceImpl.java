package com.dataocean.module.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.event.PermissionChangedEvent;
import com.dataocean.module.user.entity.dto.DepartmentCreateDTO;
import com.dataocean.module.user.entity.dto.DepartmentUpdateDTO;
import com.dataocean.module.user.entity.vo.DepartmentTreeVO;
import com.dataocean.module.user.entity.SysDepartment;
import com.dataocean.module.user.entity.SysUser;
import com.dataocean.module.user.mapper.DepartmentMapper;
import com.dataocean.module.user.mapper.UserMapper;
import com.dataocean.module.user.service.DepartmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部门管理业务实现类。
 * <p>
 * 实现 {@link DepartmentService} 接口，提供部门树查询、创建和删除的具体逻辑。
 * 部门采用 parent_id 自关联实现树形结构，在内存中组装树形层级。
 * </p>
 *
 * @author DataOcean
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DepartmentServiceImpl implements DepartmentService {

    private final DepartmentMapper departmentMapper;
    private final UserMapper userMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：
     * 1. 查询所有启用状态的部门，按排序号和 ID 排序
     * 2. 将平铺列表转换为 Map（ID → 节点）
     * 3. 遍历节点，根据 parentId 挂载到父节点的 children 列表
     * 4. 对树的每一层按 sortOrder 排序后返回根节点列表
     * </p>
     */
    @Override
    public List<DepartmentTreeVO> tree() {
        log.debug("查询部门树");
        // 查询所有启用状态的部门，按排序号和 ID 排序
        List<SysDepartment> departments = departmentMapper.selectList(new LambdaQueryWrapper<SysDepartment>()
                .eq(SysDepartment::getStatus, 1)
                .orderByAsc(SysDepartment::getSortOrder)
                .orderByAsc(SysDepartment::getId));
        // 将部门列表转换为有序 Map，便于后续按 parentId 查找父节点
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
        // 组装树形结构：将子节点挂载到对应父节点的 children 列表
        List<DepartmentTreeVO> roots = new ArrayList<>();
        for (DepartmentTreeVO node : nodeMap.values()) {
            if (node.getParentId() != null && nodeMap.containsKey(node.getParentId())) {
                nodeMap.get(node.getParentId()).getChildren().add(node);
            } else {
                // parentId 为空或父节点不存在时视为根节点
                roots.add(node);
            }
        }
        // 递归排序树的每一层
        sortTree(roots);
        log.debug("部门树查询完成 departmentCount={} rootCount={}", departments.size(), roots.size());
        return roots;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：
     * 1. 校验上级部门存在性（若指定了 parentId）
     * 2. 校验部门编码唯一性
     * 3. 构建部门实体并插入数据库
     * </p>
     */
    @Transactional
    @Override
    public Long createDepartment(DepartmentCreateDTO request) {
        log.info("开始创建部门 deptCode={} deptName={} parentId={}",
                request.getDeptCode(), request.getDeptName(), request.getParentId());
        // 校验上级部门是否存在
        if (request.getParentId() != null && departmentMapper.selectById(request.getParentId()) == null) {
            log.warn("创建部门被拒绝：上级部门不存在 parentId={}", request.getParentId());
            throw new BusinessException("上级部门不存在");
        }
        // 校验部门编码唯一性
        Long sameCode = departmentMapper.selectCount(new LambdaQueryWrapper<SysDepartment>()
                .eq(SysDepartment::getDeptCode, request.getDeptCode()));
        if (sameCode > 0) {
            throw new BusinessException("部门编码已存在");
        }
        // 构建部门实体并插入
        SysDepartment department = new SysDepartment();
        department.setParentId(request.getParentId());
        department.setDeptName(request.getDeptName());
        department.setDeptCode(request.getDeptCode());
        department.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        department.setStatus(1);
        departmentMapper.insert(department);
        eventPublisher.publishEvent(new PermissionChangedEvent(this, department.getId(), null));
        log.info("部门创建成功 departmentId={} deptCode={}", department.getId(), department.getDeptCode());
        return department.getId();
    }

    @Transactional
    @Override
    public void updateDepartment(Long id, DepartmentUpdateDTO request) {
        SysDepartment department = requireDepartment(id);
        Long parentId = normalizeParentId(request.getParentId());
        if (id.equals(parentId)) {
            throw new BusinessException("上级部门不能选择自身");
        }
        if (parentId != null) {
            requireDepartment(parentId);
            if (isDescendant(parentId, id)) {
                throw new BusinessException("上级部门不能选择当前部门的下级");
            }
        }
        Long sameCode = departmentMapper.selectCount(new LambdaQueryWrapper<SysDepartment>()
                .eq(SysDepartment::getDeptCode, request.getDeptCode())
                .ne(SysDepartment::getId, id));
        if (sameCode != null && sameCode > 0) {
            throw new BusinessException("部门编码已存在");
        }
        department.setParentId(parentId);
        department.setDeptName(request.getDeptName());
        department.setDeptCode(request.getDeptCode());
        department.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        department.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        departmentMapper.updateById(department);
        eventPublisher.publishEvent(new PermissionChangedEvent(this, id, null));
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：
     * 1. 检查部门下是否有关联用户
     * 2. 检查部门下是否有子部门
     * 3. 若部门非空则拒绝删除，否则执行物理删除
     * </p>
     */
    @Transactional
    @Override
    public void deleteDepartment(Long id) {
        log.info("开始删除部门 departmentId={}", id);
        // 检查部门下是否有关联用户
        Long userCount = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getDepartmentId, id));
        // 检查部门下是否有子部门
        Long childCount = departmentMapper.selectCount(new LambdaQueryWrapper<SysDepartment>().eq(SysDepartment::getParentId, id));
        if (userCount > 0 || childCount > 0) {
            // 数据库不创建外键，非空部门保护统一在业务层校验
            log.warn("删除部门被拒绝：部门非空 departmentId={} userCount={} childCount={}",
                    id, userCount, childCount);
            throw new BusinessException("非空部门禁止删除");
        }
        departmentMapper.deleteById(id);
        eventPublisher.publishEvent(new PermissionChangedEvent(this, id, null));
        log.info("部门删除成功 departmentId={}", id);
    }

    /**
     * 递归排序树节点列表。
     * <p>
     * 对当前层级按 sortOrder 升序排列（null 排最后），并递归处理每个节点的子节点列表。
     * </p>
     *
     * @param nodes 当前层级的节点列表
     */
    private void sortTree(List<DepartmentTreeVO> nodes) {
        nodes.sort(Comparator.comparing(DepartmentTreeVO::getSortOrder, Comparator.nullsLast(Integer::compareTo)));
        nodes.forEach(node -> sortTree(node.getChildren()));
    }

    private SysDepartment requireDepartment(Long id) {
        SysDepartment department = departmentMapper.selectById(id);
        if (department == null) {
            throw new BusinessException("部门不存在");
        }
        return department;
    }

    private Long normalizeParentId(Long parentId) {
        return parentId == null || parentId == 0 ? null : parentId;
    }

    private boolean isDescendant(Long candidateId, Long ancestorId) {
        Long current = candidateId;
        while (current != null && current != 0) {
            SysDepartment currentDepartment = departmentMapper.selectById(current);
            if (currentDepartment == null) {
                return false;
            }
            Long parentId = currentDepartment.getParentId();
            if (ancestorId.equals(parentId)) {
                return true;
            }
            current = parentId;
        }
        return false;
    }
}
