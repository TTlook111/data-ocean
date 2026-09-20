package com.dataocean.module.permission.s1.controller;

import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.permission.s1.entity.vo.IamS1CapabilitySnapshotVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1GrantTemplateVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1RoleTemplateVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1SubjectOptionVO;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * IAM-SIMPLE-1 能力摘要、中文模板与表单选择对象。
 * <p>
 * 能力摘要只用于前端界面可见性；直接调用 API 仍由 Java Controller/Service 强制校验，
 * 前端隐藏按钮不能替代后端判定。
 * </p>
 */
@RestController
@RequestMapping("/api/iam-s1")
@RequiredArgsConstructor
public class IamS1CapabilityController {

    private final IamS1CapabilityService capabilityService;

    /** 当前用户的能力摘要：全局功能、按数据源的后台能力、问数/SQL/导出能力。 */
    @GetMapping("/capabilities")
    public Result<IamS1CapabilitySnapshotVO> capabilities() {
        return Result.success(capabilityService.snapshot(UserContext.currentUserId()));
    }

    @GetMapping("/templates/role-templates")
    public Result<List<IamS1RoleTemplateVO>> roleTemplates() {
        return Result.success(capabilityService.roleTemplates());
    }

    @GetMapping("/templates/grant-templates")
    public Result<List<IamS1GrantTemplateVO>> grantTemplates() {
        return Result.success(capabilityService.grantTemplates());
    }

    /** 可选数据源：系统管理员为全部启用数据源，其他用户为负责范围。 */
    @GetMapping("/datasources")
    public Result<List<IamS1DatasourceRefVO>> datasources() {
        return Result.success(capabilityService.selectableDatasources(UserContext.currentUserId()));
    }

    /**
     * 表单选择对象：只返回必要名称。
     *
     * <p>`scope` 必填，缺失或未知值一律拒绝；`datasourceId` 对 `GRANT` / `EFFECTIVE` 必填，
     * 对 `ORGANIZATION` 不接受。判定方式见 {@code IamS1CapabilityService#subjectOptions}。</p>
     */
    @GetMapping("/subjects")
    public Result<List<IamS1SubjectOptionVO>> subjects(@RequestParam(required = false) String scope,
                                                       @RequestParam(required = false) Long datasourceId,
                                                       @RequestParam(required = false) String subjectType,
                                                       @RequestParam(required = false) String keyword) {
        return Result.success(capabilityService.subjectOptions(UserContext.currentUserId(), scope, datasourceId,
                subjectType, keyword));
    }
}
