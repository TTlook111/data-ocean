package com.dataocean.module.knowledge.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.knowledge.entity.dto.KnowledgeReleaseBuildDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeReleaseCancelDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeReleasePrepareDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeReleaseCandidateVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeReleaseDetailVO;
import com.dataocean.module.knowledge.service.KnowledgeBuildService;
import com.dataocean.module.knowledge.service.KnowledgeReleaseService;
import com.dataocean.module.knowledge.service.impl.KnowledgeBuildStream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;

/**
 * 发布候选准备及来源范围内的读取入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/knowledge/sources/{source}/releases")
public class KnowledgeReleaseController {

    /**
     * 候选固定与回读业务。
     */
    private final KnowledgeReleaseService releases;

    /**
     * 构建详情和实际工作者任务。
     */
    private final KnowledgeBuildService builds;

    /**
     * 订阅可见状态的短连接。
     */
    private final KnowledgeBuildStream streams;

    /**
     * 按当前源和选择的文档分页返回候选。
     */
    @GetMapping
    @RequirePermission("releases.view")
    public Result<PageVO<KnowledgeReleaseCandidateVO>> list(
            @PathVariable String source,
            @RequestParam(required = false) String documentId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            PageQuery page) {
        return Result.success(releases.list(
                Ids.parse(source), documentId == null ? null : Ids.parse(documentId), keyword, status, page));
    }

    /**
     * 从已核对的确认修订创建可供后续构建使用的候选。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("releases.build")
    public Result<MutationVO> prepare(
            @PathVariable String source,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgeReleasePrepareDTO input) {
        return Result.success(releases.prepare(Ids.parse(source), command, input));
    }

    /**
     * 以当前源和候选回读页面快照。
     */
    @GetMapping("/{id}")
    @RequirePermission("releases.view")
    public Result<KnowledgeReleaseDetailVO> detail(@PathVariable String source, @PathVariable String id) {
        return Result.success(builds.detail(Ids.parse(source), Ids.parse(id)));
    }

    /**
     * 构建进度推送与刷新回读共用候选和来源边界。
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequirePermission("releases.view")
    public SseEmitter events(@PathVariable String source, @PathVariable String id, HttpServletRequest request) {
        return streams.open(
                Ids.parse(source), Ids.parse(id), request.getSession(false).getId());
    }

    /**
     * 受理异步构建并返回可核实的原任务编号。
     */
    @PostMapping("/{id}/builds")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequirePermission("releases.build")
    public Result<MutationVO> build(
            @PathVariable String source,
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgeReleaseBuildDTO input) {
        return Result.success(builds.start(Ids.parse(source), Ids.parse(id), command, input));
    }

    /**
     * 取消尚未生效的当前发布任务并记录原因。
     */
    @PostMapping("/{id}/cancellations")
    @RequirePermission("releases.build")
    public Result<MutationVO> cancel(
            @PathVariable String source,
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody KnowledgeReleaseCancelDTO input) {
        return Result.success(builds.cancel(Ids.parse(source), Ids.parse(id), command, input));
    }
}
