package com.dataocean.module.iam.controller;

import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.iam.entity.dto.AddSecondaryMembersDTO;
import com.dataocean.module.iam.entity.dto.CreateDepartmentDTO;
import com.dataocean.module.iam.entity.dto.CreatePreviewDTO;
import com.dataocean.module.iam.entity.dto.DepartmentImpactPreviewDTO;
import com.dataocean.module.iam.entity.dto.DepartmentProfileDTO;
import com.dataocean.module.iam.entity.dto.EnableDepartmentDTO;
import com.dataocean.module.iam.entity.dto.EndMembershipDTO;
import com.dataocean.module.iam.entity.dto.MoveChildDepartmentsDTO;
import com.dataocean.module.iam.entity.dto.MoveDepartmentMembersDTO;
import com.dataocean.module.iam.entity.dto.UpdateDepartmentDTO;
import com.dataocean.module.iam.entity.dto.VersionActionDTO;
import com.dataocean.module.iam.entity.query.DepartmentTreeQuery;
import com.dataocean.module.iam.entity.query.MemberQuery;
import com.dataocean.module.iam.entity.vo.DepartmentDetailVO;
import com.dataocean.module.iam.entity.vo.DepartmentMemberVO;
import com.dataocean.module.iam.entity.vo.DepartmentTreeVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.service.DepartmentService;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Department 的类型化 HTTP 入口，业务核验委托所属服务。
 */
@RestController
@RequestMapping("/api/v1/iam/departments")
@RequiredArgsConstructor
public class DepartmentController {

    /**
     * 返回当前部门页签中的直属子部门，查询范围与分页总数一致。
     */
    @GetMapping("/{id}/children")
    public Result<PageVO<DepartmentDetailVO>> children(
            @PathVariable String id, @Valid @ModelAttribute PageQuery query) {
        return Result.success(service.children(id, query));
    }

    /**
     * 当前模块的业务服务。
     */
    private final DepartmentService service;

    /**
     * 按筛选条件读取部门树及必要祖先。
     */
    @GetMapping("/tree")
    public Result<List<DepartmentTreeVO>> tree(@Valid @ModelAttribute DepartmentTreeQuery query) {
        return Result.success(service.tree(query));
    }

    /**
     * 读取部门详情及去重成员统计。
     */
    @GetMapping("/{id}")
    public Result<DepartmentDetailVO> detail(@PathVariable String id) {
        return Result.success(service.detail(id));
    }

    /**
     * 分页读取直属或后代范围的成员。
     */
    @GetMapping("/{id}/members")
    public Result<PageVO<DepartmentMemberVO>> members(
            @PathVariable String id, @Valid @ModelAttribute MemberQuery query) {
        return Result.success(service.members(id, query));
    }

    /**
     * 核对新增部门的完整配置。
     */
    @PostMapping("/impact-preview")
    public Result<ImpactPreviewVO> previewCreate(@Valid @RequestBody CreatePreviewDTO<DepartmentProfileDTO> data) {
        return Result.success(service.previewCreate(data));
    }

    /**
     * 创建根部门或子部门。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<MutationVO> create(
            @Valid @RequestBody CreateDepartmentDTO data, @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.create(data, command));
    }

    /**
     * 核对目标部门的动作及全部影响。
     */
    @PostMapping("/{id}/impact-preview")
    public Result<ImpactPreviewVO> preview(
            @PathVariable String id, @Valid @RequestBody DepartmentImpactPreviewDTO data) {
        return Result.success(service.preview(id, data));
    }

    /**
     * 保存部门资料并阻止组织成环。
     */
    @PutMapping("/{id}")
    public Result<MutationVO> update(
            @PathVariable String id,
            @Valid @RequestBody UpdateDepartmentDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.update(id, data, command));
    }

    /**
     * 一次加入整批兼任成员。
     */
    @PostMapping("/{id}/secondary-members")
    public Result<MutationVO> addMembers(
            @PathVariable String id,
            @Valid @RequestBody AddSecondaryMembersDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.addMembers(id, data, command));
    }

    /**
     * 整批迁移全部直属成员。
     */
    @PostMapping("/{id}/member-transfers")
    public Result<MutationVO> moveMembers(
            @PathVariable String id,
            @Valid @RequestBody MoveDepartmentMembersDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.moveMembers(id, data, command));
    }

    /**
     * 整批迁移全部直属子部门。
     */
    @PostMapping("/{id}/child-transfers")
    public Result<MutationVO> moveChildren(
            @PathVariable String id,
            @Valid @RequestBody MoveChildDepartmentsDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.moveChildren(id, data, command));
    }

    /**
     * 复核成员和保留配置后恢复组织。
     */
    @PostMapping("/{id}/enable")
    public Result<MutationVO> enable(
            @PathVariable String id,
            @Valid @RequestBody EnableDepartmentDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.enable(id, data, command));
    }

    /**
     * 停用组织，保持成员账号不变。
     */
    @PostMapping("/{id}/disable")
    public Result<MutationVO> disable(
            @PathVariable String id,
            @Valid @RequestBody VersionActionDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.lifecycle(id, "DISABLE", data, command));
    }

    /**
     * 核对关联已处理后归档部门。
     */
    @PostMapping("/{id}/archive")
    public Result<MutationVO> archive(
            @PathVariable String id,
            @Valid @RequestBody VersionActionDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.lifecycle(id, "ARCHIVE", data, command));
    }

    /**
     * 只结束指定的兼任关系。
     */
    @PostMapping("/{id}/memberships/{membershipId}/end")
    public Result<MutationVO> endMembership(
            @PathVariable String id,
            @PathVariable String membershipId,
            @Valid @RequestBody EndMembershipDTO data,
            @RequestHeader("Idempotency-Key") String command) {
        return Result.success(service.endMembership(id, membershipId, data, command));
    }
}
