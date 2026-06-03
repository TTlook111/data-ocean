package com.dataocean.module.user.service;

import com.dataocean.module.user.entity.dto.DepartmentCreateDTO;
import com.dataocean.module.user.entity.dto.DepartmentUpdateDTO;
import com.dataocean.module.user.entity.vo.DepartmentTreeVO;

import java.util.List;

/**
 * 部门管理业务接口。
 * <p>
 * 提供部门树查询、创建和删除功能。
 * 部门采用 parent_id 自关联实现树形结构，支持多级嵌套。
 * </p>
 *
 * @author DataOcean
 */
public interface DepartmentService {

    /**
     * 查询完整的部门树结构。
     * <p>
     * 查询所有启用状态的部门，按 sortOrder 和 ID 排序后组装为树形结构返回。
     * </p>
     *
     * @return 部门树根节点列表（每个节点包含子节点列表）
     */
    List<DepartmentTreeVO> tree();

    /**
     * 创建新部门。
     * <p>
     * 校验上级部门存在性和部门编码唯一性后创建部门记录。
     * </p>
     *
     * @param request 部门创建请求参数（含部门名称、编码、上级部门 ID 等）
     * @return 新创建部门的 ID
     */
    Long createDepartment(DepartmentCreateDTO request);

    void updateDepartment(Long id, DepartmentUpdateDTO request);

    /**
     * 删除部门。
     * <p>
     * 仅允许删除空部门（无子部门且无关联用户），否则抛出业务异常。
     * </p>
     *
     * @param id 部门 ID
     */
    void deleteDepartment(Long id);
}
