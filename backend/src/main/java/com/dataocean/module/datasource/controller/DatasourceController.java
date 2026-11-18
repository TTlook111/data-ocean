package com.dataocean.module.datasource.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.datasource.entity.dto.ConnectionCheckDTO;
import com.dataocean.module.datasource.entity.dto.CredentialChangeDTO;
import com.dataocean.module.datasource.entity.dto.ScheduleChangeDTO;
import com.dataocean.module.datasource.entity.dto.SourceCreateDTO;
import com.dataocean.module.datasource.entity.dto.SourceCreateProposalDTO;
import com.dataocean.module.datasource.entity.dto.SourceEnableDTO;
import com.dataocean.module.datasource.entity.dto.SourceUpdateDTO;
import com.dataocean.module.datasource.entity.query.SourceQuery;
import com.dataocean.module.datasource.entity.vo.ConnectionCheckVO;
import com.dataocean.module.datasource.entity.vo.CredentialChangeVO;
import com.dataocean.module.datasource.entity.vo.SourceDetailVO;
import com.dataocean.module.datasource.entity.vo.SourceRowVO;
import com.dataocean.module.datasource.security.DatasourceRequestPermission;
import com.dataocean.module.datasource.service.DatasourceService;
import com.dataocean.module.datasource.service.impl.CredentialMaintenance;
import com.dataocean.module.datasource.service.impl.SourceManagement;
import com.dataocean.module.datasource.service.impl.SourceManagement.SourceOption;
import com.dataocean.module.iam.entity.dto.CreatePreviewDTO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.UserOptionVO;
import com.dataocean.module.iam.security.RequirePermission;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 数据源管理的草稿、新建与分项测试入口。
 */
@RestController
@RequestMapping("/api/v1/datasources")
@RequiredArgsConstructor
public class DatasourceController {

    /**
     * 处理数据源管理动作及连接证据的业务服务。
     */
    private final DatasourceService sources;

    /**
     * 查询和变更已存在来源与采集计划。
     */
    private final SourceManagement management;

    /**
     * 按真实停止事实处理来源凭据切换。
     */
    private final CredentialMaintenance credentials;

    /**
     * 数据源管理列表复用公共分页。
     */
    @GetMapping
    @RequirePermission("sources.view")
    public Result<PageVO<SourceRowVO>> list(SourceQuery query) {
        return Result.success(management.list(query));
    }

    /**
     * 仅管理权限可以读取来源连接配置。
     */
    @GetMapping("/{sourceId}")
    @RequirePermission("sources.view")
    public Result<SourceDetailVO> detail(@PathVariable String sourceId) {
        return Result.success(management.detail(Ids.parse(sourceId)));
    }

    /**
     * 元数据目录与采集记录的选项范围分别核权。
     */
    @GetMapping("/options")
    @RequirePermission(policy = DatasourceRequestPermission.class)
    public Result<List<SourceOption>> options(@RequestParam String purpose) {
        return Result.success(management.options());
    }

    /**
     * 只返回当前可选的联系人和安全引用。
     */
    @GetMapping("/contact-options")
    @RequirePermission(anyOf = {"sources.create", "sources.edit"})
    public Result<PageVO<UserOptionVO>> contacts(@RequestParam(required = false) String keyword, PageQuery query) {
        return Result.success(management.contacts(keyword, query));
    }

    /**
     * 保存核对过的非秘密配置与草稿凭据。
     */
    @PutMapping("/{sourceId}/configuration")
    @RequirePermission("sources.edit")
    public Result<MutationVO> edit(
            @PathVariable String sourceId,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody SourceUpdateDTO input) {
        return Result.success(management.edit(Ids.parse(sourceId), command, input));
    }

    /**
     * 用本源有效测试启用已有草稿。
     */
    @PostMapping("/{sourceId}/enable")
    @RequirePermission("sources.enable")
    public Result<MutationVO> enable(
            @PathVariable String sourceId,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody SourceEnableDTO input) {
        return Result.success(management.enable(Ids.parse(sourceId), command, input));
    }

    /**
     * 暂停或恢复未来的定时接收。
     */
    @PostMapping("/{sourceId}/schedule/state")
    @RequirePermission("sources.pause")
    public Result<SourceDetailVO> schedule(
            @PathVariable String sourceId,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody ScheduleChangeDTO input) {
        return Result.success(management.pause(Ids.parse(sourceId), command, input));
    }

    /**
     * 接收凭据切换，实际结果由替换状态持续回读。
     */
    @PostMapping("/{sourceId}/credential-changes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequirePermission("sources.edit")
    public Result<CredentialChangeVO> replace(
            @PathVariable String sourceId,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody CredentialChangeDTO input) {
        return Result.success(credentials.replace(Ids.parse(sourceId), command, input));
    }

    /**
     * 回看原操作及其真实停止状态。
     */
    @GetMapping("/{sourceId}/credential-changes/{changeId}")
    @RequirePermission("sources.view")
    public Result<CredentialChangeVO> replacement(@PathVariable String sourceId, @PathVariable String changeId) {
        return Result.success(credentials.detail(Ids.parse(sourceId), changeId));
    }

    /**
     * 回包丢失时使用同一原命令定位替换。
     */
    @GetMapping("/{sourceId}/credential-changes/by-command/{commandId}")
    @RequirePermission("sources.edit")
    public Result<CredentialChangeVO> replacementByCommand(
            @PathVariable String sourceId, @PathVariable String commandId) {
        return Result.success(credentials.byCommand(Ids.parse(sourceId), commandId));
    }

    /**
     * 在新建正式源之前测试草稿连接，返回存档的逐项原因。
     */
    @PostMapping("/connection-checks")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("sources.test")
    public Result<ConnectionCheckVO> test(
            @RequestHeader(value = "Idempotency-Key", required = false) String command,
            @Valid @RequestBody ConnectionCheckDTO input) {
        return Result.success(sources.test(null, command, input));
    }

    /**
     * 在当前源配置约束内测试拟修改连接或凭据。
     */
    @PostMapping("/{sourceId}/connection-checks")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("sources.test")
    public Result<ConnectionCheckVO> testSaved(
            @PathVariable String sourceId,
            @RequestHeader(value = "Idempotency-Key", required = false) String command,
            @Valid @RequestBody ConnectionCheckDTO input) {
        return Result.success(sources.test(Ids.parse(sourceId), command, input));
    }

    /**
     * 查看原检查回执，无须为刷新页面重新连接业务库。
     */
    @GetMapping("/connection-checks/{checkId}")
    @RequirePermission("sources.test")
    public Result<ConnectionCheckVO> check(@PathVariable String checkId) {
        return Result.success(sources.check(Ids.parse(checkId)));
    }

    /**
     * 为新增或启用源签发同一操作者的只读核对凭据。
     */
    @PostMapping("/impact-preview")
    @RequirePermission(policy = DatasourceRequestPermission.class)
    public Result<ImpactPreviewVO> review(@Valid @RequestBody CreatePreviewDTO<SourceCreateProposalDTO> input) {
        return Result.success(sources.reviewCreate(input.getProposal()));
    }

    /**
     * 以防重命令提交已核对的草稿或启用资料。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(policy = DatasourceRequestPermission.class)
    public Result<MutationVO> create(
            @RequestHeader("Idempotency-Key") String command, @Valid @RequestBody SourceCreateDTO input) {
        return Result.success(sources.create(command, input));
    }
}
