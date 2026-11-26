package com.dataocean.module.access.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.access.entity.dto.AccessRequestDTO;
import com.dataocean.module.access.entity.dto.DepartmentAccessDTO;
import com.dataocean.module.access.entity.dto.OutputRuleDTO;
import com.dataocean.module.access.entity.dto.RequestActionDTO;
import com.dataocean.module.access.entity.dto.ResponsibilityDTO;
import com.dataocean.module.access.entity.dto.RevokeAccessPreviewDTO;
import com.dataocean.module.access.entity.query.AccessListQuery;
import com.dataocean.module.access.entity.vo.AccessGrantVO;
import com.dataocean.module.access.entity.vo.AccessRequestVO;
import com.dataocean.module.access.entity.vo.AccessScopeVO;
import com.dataocean.module.access.entity.vo.AccessSourceOptionVO;
import com.dataocean.module.access.entity.vo.DepartmentAccessVO;
import com.dataocean.module.access.entity.vo.DepartmentGrantVO;
import com.dataocean.module.access.entity.vo.GrantDirectoryVO;
import com.dataocean.module.access.entity.vo.OutputRuleVO;
import com.dataocean.module.access.entity.vo.RequestActivityVO;
import com.dataocean.module.access.entity.vo.ResponsibilityVO;
import com.dataocean.module.access.service.AccessConfigurationService;
import com.dataocean.module.access.service.AccessRequestService;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.iam.entity.dto.VersionActionDTO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.UserOptionVO;
import com.dataocean.module.iam.security.RequirePermission;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 数据访问授权的人员端及管理端接口。
 */
@RestController
@RequestMapping("/api/v1/access")
@RequiredArgsConstructor
public class AccessController {

    /**
     * 共享规则、部门与责任配置。
     */
    private final AccessConfigurationService configuration;

    /**
     * 申请和按具体人员分派的办理业务。
     */
    private final AccessRequestService requests;

    /**
     * 当前访问资格及历史来源撤销。
     */
    private final AccessScopeService scopes;

    /**
     * 分页查看共享规则。
     */
    @GetMapping("/rules")
    @RequirePermission("output-rules.view")
    public Result<PageVO<OutputRuleVO>> rules(AccessListQuery query) {
        return Result.success(configuration.rules(query));
    }

    /**
     * 办理者查看可选规则及全文。
     */
    @GetMapping("/rule-options")
    @RequirePermission(anyOf = {"access-requests.review", "departments.access"})
    public Result<PageVO<OutputRuleVO>> ruleOptions(AccessListQuery query) {
        return Result.success(configuration.rules(query));
    }

    /**
     * 新增共享规则。
     */
    @PostMapping("/rules")
    @RequirePermission("output-rules.create")
    public Result<MutationVO> createRule(
            @RequestHeader("Idempotency-Key") String command, @Valid @RequestBody OutputRuleDTO input) {
        return Result.success(configuration.saveRule(null, command, input));
    }

    /**
     * 修改现有规则并更新适用的实际限制。
     */
    @PutMapping("/rules/{id}")
    @RequirePermission("output-rules.edit")
    public Result<MutationVO> editRule(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody OutputRuleDTO input) {
        return Result.success(configuration.saveRule(Ids.parse(id), command, input));
    }

    /**
     * 读取编辑规则后实际受影响的来源。
     */
    @PostMapping("/rules/{id}/impact-preview")
    @RequirePermission("output-rules.edit")
    public Result<ImpactPreviewVO> previewRule(@PathVariable String id, @Valid @RequestBody OutputRuleDTO input) {
        return Result.success(configuration.previewRule(Ids.parse(id), input));
    }

    /**
     * 只提供办理需要且已启用的数据源引用。
     */
    @GetMapping("/source-options")
    @RequirePermission(
            anyOf = {
                "my-requests.apply",
                "access-requests.route",
                "access-requests.view",
                "departments.access",
                "grants.view",
                "business-notes.view"
            })
    public Result<PageVO<AccessSourceOptionVO>> sourceOptions(AccessListQuery query) {
        return Result.success(configuration.sourceOptions(query));
    }

    /**
     * 只提供当前具备审批能力的账号。
     */
    @GetMapping("/approver-options")
    @RequirePermission("sources.approval-config")
    public Result<PageVO<UserOptionVO>> approvers(@RequestParam(required = false) String keyword, PageQuery query) {
        return Result.success(configuration.approvers(keyword, query));
    }

    /**
     * 按实际审批资格展示源责任。
     */
    @GetMapping("/responsibilities")
    @RequirePermission("sources.view")
    public Result<PageVO<ResponsibilityVO>> responsibilities(AccessListQuery query) {
        return Result.success(configuration.responsibilities(query));
    }

    /**
     * 保存已核对的主审批人和替代负责人。
     */
    @PutMapping("/responsibilities/{id}")
    @RequirePermission("sources.approval-config")
    public Result<MutationVO> responsibility(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody ResponsibilityDTO input) {
        return Result.success(configuration.saveResponsibility(Ids.parse(id), command, input));
    }

    /**
     * 主要责任和替代责任按当前源及人员版本核对。
     */
    @PostMapping("/responsibilities/{id}/impact-preview")
    @RequirePermission("sources.approval-config")
    public Result<ImpactPreviewVO> previewResponsibility(
            @PathVariable String id, @Valid @RequestBody ResponsibilityDTO input) {
        return Result.success(configuration.previewResponsibility(Ids.parse(id), input));
    }

    /**
     * 回看本人申请。
     */
    @GetMapping("/my-requests")
    @RequirePermission("my-requests.view")
    public Result<PageVO<AccessRequestVO>> mine(AccessListQuery query) {
        return Result.success(requests.list(query, true));
    }

    /**
     * 仅展示进入当前真实处理范围的申请。
     */
    @GetMapping("/requests")
    @RequirePermission("access-requests.view")
    public Result<PageVO<AccessRequestVO>> queue(AccessListQuery query) {
        return Result.success(requests.list(query, false));
    }

    /**
     * 本人和实际办理人按可见范围回看原申请。
     */
    @GetMapping("/requests/{id}")
    @RequirePermission(anyOf = {"my-requests.view", "access-requests.view", "grants.view"})
    public Result<AccessRequestVO> request(@PathVariable String id) {
        return Result.success(requests.detail(Ids.parse(id)));
    }

    /**
     * 申请人回看原单据的持久办理动作。
     */
    @GetMapping("/my-requests/{id}/history")
    @RequirePermission("my-requests.view")
    public Result<List<RequestActivityVO>> requestHistory(@PathVariable String id) {
        return Result.success(requests.activities(Ids.parse(id)));
    }

    /**
     * 审批结果按当前人员的真实办理记录回看。
     */
    @GetMapping("/requests/{id}/handled")
    @RequirePermission("access-requests.review")
    public Result<AccessRequestVO> handled(@PathVariable String id) {
        return Result.success(requests.handledDetail(Ids.parse(id)));
    }

    /**
     * 创建本人草稿或提交。
     */
    @PostMapping("/my-requests")
    @RequirePermission("my-requests.apply")
    public Result<MutationVO> apply(
            @RequestHeader("Idempotency-Key") String command, @Valid @RequestBody AccessRequestDTO input) {
        return Result.success(requests.save(null, command, input));
    }

    /**
     * 保存本人草稿或重提补充内容。
     */
    @PutMapping("/my-requests/{id}")
    @RequirePermission("my-requests.apply")
    public Result<MutationVO> resubmit(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody AccessRequestDTO input) {
        return Result.success(requests.save(Ids.parse(id), command, input));
    }

    /**
     * 申请人核对提交范围，不执行写入。
     */
    @PostMapping("/my-requests/submission-preview")
    @RequirePermission("my-requests.apply")
    public Result<ImpactPreviewVO> previewSubmission(
            @RequestParam(required = false) String id, @Valid @RequestBody AccessRequestDTO input) {
        return Result.success(requests.previewSubmission(Ids.nullable(id), input));
    }

    /**
     * 接收真实办理动作，业务层按动作重新核权。
     */
    @PostMapping("/requests/{id}/actions")
    @RequirePermission(
            anyOf = {
                "access-requests.route",
                "access-requests.review",
                "access-requests.supplement",
                "access-requests.reject",
                "my-requests.withdraw"
            })
    public Result<MutationVO> act(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody RequestActionDTO input) {
        return Result.success(requests.act(Ids.parse(id), command, input));
    }

    /**
     * 读取当前办理上下文并建立变更核对。
     */
    @PostMapping("/requests/{id}/action-preview")
    @RequirePermission(
            anyOf = {
                "access-requests.route",
                "access-requests.review",
                "access-requests.supplement",
                "access-requests.reject",
                "my-requests.withdraw"
            })
    public Result<ImpactPreviewVO> previewAction(@PathVariable String id, @Valid @RequestBody RequestActionDTO input) {
        return Result.success(requests.preview(Ids.parse(id), input));
    }

    /**
     * 回看直属部门已有初始配置。
     */
    @GetMapping("/departments/{id}")
    @RequirePermission("departments.view")
    public Result<DepartmentAccessVO> department(@PathVariable String id) {
        return Result.success(configuration.department(Ids.parse(id)));
    }

    /**
     * 保存或结束部门初始访问，实际动作分别核权。
     */
    @PutMapping("/departments/{id}")
    @RequirePermission(anyOf = {"departments.access", "departments.end-access"})
    public Result<MutationVO> saveDepartment(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody DepartmentAccessDTO input) {
        return Result.success(configuration.saveDepartment(Ids.parse(id), command, input));
    }

    /**
     * 部门影响只计算当前直属或直属兼任成员。
     */
    @PostMapping("/departments/{id}/impact-preview")
    @RequirePermission(anyOf = {"departments.access", "departments.end-access"})
    public Result<ImpactPreviewVO> previewDepartment(
            @PathVariable String id, @Valid @RequestBody DepartmentAccessDTO input) {
        return Result.success(configuration.previewDepartment(Ids.parse(id), input));
    }

    /**
     * 回读本人实时访问授权。
     */
    @GetMapping("/my-sources")
    @RequirePermission("my-access.view")
    public Result<List<AccessScopeVO>> scope() {
        return Result.success(scopes.current(UserContext.getUser()));
    }

    /**
     * 具备人员查看权时回看相应当前数据访问。
     */
    @GetMapping("/users/{id}/sources")
    @RequirePermission("users.view")
    public Result<List<AccessScopeVO>> userScope(@PathVariable String id) {
        return Result.success(scopes.current(Ids.parse(id)));
    }

    /**
     * 回看真实批准来源及撤销历史。
     */
    @GetMapping("/grants")
    @RequirePermission("grants.view")
    public Result<PageVO<AccessGrantVO>> grants(AccessListQuery query) {
        return Result.success(scopes.grants(query));
    }

    /**
     * 同一列表对申请授权和部门来源进行真实统一查询与分页。
     */
    @GetMapping("/grant-directory")
    @RequirePermission("grants.view")
    public Result<PageVO<GrantDirectoryVO>> grantDirectory(AccessListQuery query) {
        return Result.success(scopes.grantDirectory(query));
    }

    /**
     * 管理人员按部门、人员及当前状态回看真实部门来源。
     */
    @GetMapping("/department-grants")
    @RequirePermission("grants.view")
    public Result<PageVO<DepartmentGrantVO>> departmentGrants(AccessListQuery query) {
        return Result.success(scopes.departmentGrants(null, query));
    }

    /**
     * 本人申请产生的全部来源，包括已撤销记录。
     */
    @GetMapping("/my-grants")
    @RequirePermission("my-access.view")
    public Result<PageVO<AccessGrantVO>> myGrants(AccessListQuery query) {
        return Result.success(scopes.userGrants(UserContext.getUser(), query));
    }

    /**
     * 本人历次有效直属关系形成的部门来源。
     */
    @GetMapping("/my-department-grants")
    @RequirePermission("my-access.view")
    public Result<PageVO<DepartmentGrantVO>> myDepartmentGrants(AccessListQuery query) {
        return Result.success(scopes.departmentGrants(UserContext.getUser(), query));
    }

    /**
     * 核对实际来源失权及同源其他来源。
     */
    @PostMapping("/grants/{id}/revoke-preview")
    @RequirePermission("grants.revoke")
    public Result<ImpactPreviewVO> previewRevoke(
            @PathVariable String id, @Valid @RequestBody RevokeAccessPreviewDTO input) {
        return Result.success(scopes.previewRevoke(Ids.parse(id), input.getExpectedVersion(), input.getReason()));
    }

    /**
     * 提交独立来源或完整联合组合的撤销。
     */
    @PostMapping("/grants/{id}/revoke")
    @RequirePermission("grants.revoke")
    public Result<MutationVO> revoke(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody VersionActionDTO input) {
        return Result.success(scopes.revoke(Ids.parse(id), command, input));
    }
}
