package com.dataocean.module.governance.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.governance.entity.dto.GovernanceConfirmDTO;
import com.dataocean.module.governance.entity.dto.GovernanceControlDTO;
import com.dataocean.module.governance.entity.dto.GovernanceDraftDTO;
import com.dataocean.module.governance.entity.dto.GovernanceGapDTO;
import com.dataocean.module.governance.entity.dto.GovernanceImpactTargetsDTO;
import com.dataocean.module.governance.entity.query.GovernanceColumnQuery;
import com.dataocean.module.governance.entity.query.GovernanceHistoryQuery;
import com.dataocean.module.governance.entity.query.GovernanceTableQuery;
import com.dataocean.module.governance.entity.vo.GovernanceColumnRowVO;
import com.dataocean.module.governance.entity.vo.GovernanceHistoryRowVO;
import com.dataocean.module.governance.entity.vo.GovernanceItemVO;
import com.dataocean.module.governance.entity.vo.GovernanceMutationVO;
import com.dataocean.module.governance.entity.vo.GovernanceNoteChangeVO;
import com.dataocean.module.governance.entity.vo.GovernanceObjectVO;
import com.dataocean.module.governance.entity.vo.GovernanceTablePageVO;
import com.dataocean.module.governance.service.GovernanceCommandService;
import com.dataocean.module.governance.service.GovernanceQueryService;
import com.dataocean.module.governance.service.GovernanceTarget;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.security.RequirePermission;

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
 * 对外承接表说明、字段治理和来源变化复核。
 */
@RestController
@RequestMapping("/api/v1/governance/sources/{sourceId}")
@RequiredArgsConstructor
public class GovernanceController {

    /**
     * 稳定条目的公用路径结构。
     */
    private static final String ITEM = "/objects/{objectId}/items/{kind}/{targetId}";

    /**
     * 当前目录、历史及其他只读投影。
     */
    private final GovernanceQueryService queries;

    /**
     * 治理草稿、审核及影响写入服务。
     */
    private final GovernanceCommandService commands;

    /**
     * 列出当前成功目录的治理进度。
     */
    @GetMapping("/objects")
    @RequirePermission("governance.view")
    public Result<GovernanceTablePageVO> tables(@PathVariable String sourceId, GovernanceTableQuery query) {
        return Result.success(queries.tables(Ids.parse(sourceId), query));
    }

    /**
     * 返回表详情及当前待办定位。
     */
    @GetMapping("/objects/{objectId}")
    @RequirePermission("governance.view")
    public Result<GovernanceObjectVO> object(@PathVariable String sourceId, @PathVariable String objectId) {
        return Result.success(queries.object(Ids.parse(sourceId), Ids.parse(objectId)));
    }

    /**
     * 分页查找该对象的实际字段。
     */
    @GetMapping("/objects/{objectId}/columns")
    @RequirePermission("governance.view")
    public Result<PageVO<GovernanceColumnRowVO>> columns(
            @PathVariable String sourceId, @PathVariable String objectId, GovernanceColumnQuery query) {
        return Result.success(queries.columns(Ids.parse(sourceId), Ids.parse(objectId), query));
    }

    /**
     * 阅读条目业务内容和被授权的实际来源。
     */
    @GetMapping(ITEM)
    @RequirePermission("governance.view")
    public Result<GovernanceItemVO> item(
            @PathVariable String sourceId,
            @PathVariable String objectId,
            @PathVariable String kind,
            @PathVariable String targetId) {
        return Result.success(queries.item(target(sourceId, objectId, kind, targetId)));
    }

    /**
     * 追溯固定修订及确认结果。
     */
    @GetMapping(ITEM + "/history")
    @RequirePermission("governance.view")
    public Result<PageVO<GovernanceHistoryRowVO>> history(
            @PathVariable String sourceId,
            @PathVariable String objectId,
            @PathVariable String kind,
            @PathVariable String targetId,
            GovernanceHistoryQuery query) {
        return Result.success(queries.history(target(sourceId, objectId, kind, targetId), query));
    }

    /**
     * 保存条目当前业务草稿。
     */
    @PutMapping(ITEM + "/draft")
    @RequirePermission("governance.edit")
    public Result<GovernanceMutationVO> draft(
            @PathVariable String sourceId,
            @PathVariable String objectId,
            @PathVariable String kind,
            @PathVariable String targetId,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody GovernanceDraftDTO input) {
        return Result.success(commands.draft(target(sourceId, objectId, kind, targetId), command, input));
    }

    /**
     * 记录影响使用的具体人工待核对问题。
     */
    @PostMapping(ITEM + "/gaps")
    @RequirePermission("governance.supplement")
    public Result<GovernanceMutationVO> supplement(
            @PathVariable String sourceId,
            @PathVariable String objectId,
            @PathVariable String kind,
            @PathVariable String targetId,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody GovernanceGapDTO input) {
        return Result.success(commands.supplement(target(sourceId, objectId, kind, targetId), command, input));
    }

    /**
     * 按实质内容确认治理，或办理已确证的移除。
     */
    @PostMapping(ITEM + "/confirmations")
    @RequirePermission("governance.confirm")
    public Result<GovernanceMutationVO> confirm(
            @PathVariable String sourceId,
            @PathVariable String objectId,
            @PathVariable String kind,
            @PathVariable String targetId,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody GovernanceConfirmDTO input) {
        return Result.success(commands.confirm(target(sourceId, objectId, kind, targetId), command, input));
    }

    /**
     * 阅读同源未定位的实际资料变化。
     */
    @GetMapping("/unmapped-note-changes")
    @RequirePermission("governance.view")
    public Result<PageVO<GovernanceNoteChangeVO>> unmapped(@PathVariable String sourceId, PageQuery query) {
        return Result.success(queries.noteChanges(Ids.parse(sourceId), null, query));
    }

    /**
     * 回看一份获准资料的治理办理结果。
     */
    @GetMapping("/note-changes")
    @RequirePermission({"governance.view", "business-notes.view"})
    public Result<PageVO<GovernanceNoteChangeVO>> noteChanges(
            @PathVariable String sourceId, @RequestParam String noteId, PageQuery query) {
        return Result.success(queries.noteChanges(Ids.parse(sourceId), Ids.parse(noteId), query));
    }

    /**
     * 选择实际影响的同源条目。
     */
    @PostMapping("/unmapped-note-changes/{changeId}/targets")
    @RequirePermission({"governance.edit", "business-notes.view"})
    public Result<GovernanceNoteChangeVO> associate(
            @PathVariable String sourceId,
            @PathVariable String changeId,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody GovernanceImpactTargetsDTO input) {
        return Result.success(commands.associate(Ids.parse(sourceId), Ids.parse(changeId), command, input));
    }

    /**
     * 先核对危险操作的实际影响。
     */
    @PostMapping(ITEM + "/impact-preview")
    @RequirePermission("governance.forbid")
    public Result<ImpactPreviewVO> preview(
            @PathVariable String sourceId,
            @PathVariable String objectId,
            @PathVariable String kind,
            @PathVariable String targetId,
            @Valid @RequestBody GovernanceControlDTO input) {
        return Result.success(commands.preview(target(sourceId, objectId, kind, targetId), input));
    }

    /**
     * 按已核对的当前影响执行人工禁止。
     */
    @PostMapping(ITEM + "/usage-control")
    @RequirePermission("governance.forbid")
    public Result<GovernanceMutationVO> forbid(
            @PathVariable String sourceId,
            @PathVariable String objectId,
            @PathVariable String kind,
            @PathVariable String targetId,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody GovernanceControlDTO input) {
        return Result.success(commands.forbid(target(sourceId, objectId, kind, targetId), command, input));
    }

    /**
     * 使用公共编号解析与治理身份约束。
     */
    private GovernanceTarget target(String source, String object, String kind, String id) {
        return GovernanceTarget.parse(Ids.parse(source), Ids.parse(object), kind, id);
    }
}
